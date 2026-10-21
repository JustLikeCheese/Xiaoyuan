package io.github.justlikecheese.xiaoyuan.core

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** 守护的状态：running 为在跑且最近按时检测过；state 为 online / offline / unreachable / wifi-down / wifi-off / other。 */
data class GuardStatus(
    val running: Boolean, val at: Long, val state: String, val pingMs: String, val root: Boolean, val pssKb: Int = 0, val version: Int = 0,
    val started: Long = 0,   // 守护启动的时间（秒）
)

/**
 * 保活守护（src/main/cpp/guardd.c，打包成 lib/<abi>/libguardd.so）：一个 C 小程序，负责 Wi-Fi 断开重连、
 * 定时 ping 门户和查在线状态，掉线时拉起应用登录。它不属于应用进程，常驻内存约 0.6 MB，平时睡着不占 CPU，
 * 应用可以完全退出。需要 root 或 shell 身份才能启动（都是执行数据目录里的 start.sh）：
 *  - Root：su 执行；
 *  - ADB：用户在电脑上执行 adbCommand() 给出的命令。
 * 数据目录是应用的外部私有目录（Android/data/包名/files/guard），应用、shell、root 都能读写。
 * 守护本身不写文字，和应用之间只有一个二进制文件 guard.bin（下面的协议，和 guardd.c 一致），日志由这里翻译。
 * 会执行 su 的方法都要在后台线程调。
 */
object Guard {
    const val SERVICE = "service"
    const val ROOT = "root"
    const val ADB = "adb"
    val MODES = listOf(SERVICE, ROOT, ADB)

    // ---- 协议（和 guardd.c 一致，小端） ----
    private const val MAGIC = 0x31475958          // "XYG1"
    private const val HEADER = 64
    private const val OLD_HEADER = 48              // 第 3 版及以前的头部长度
    private const val RING = 256
    private const val RECORD = 8
    private const val SIZE = HEADER + RING * RECORD
    private const val OFF_INTERVAL = 4             // u16 检测间隔（秒）
    private const val OFF_FLAGS = 6                // u8  FLAG_*
    private const val OFF_CHECK = 7                // u8  立即检测：每请求一次加一
    private const val OFF_IP = 8                   // 4 字节门户 IPv4（网络字节序）
    private const val OFF_PORT = 12                // u16
    private const val OFF_PID = 16                 // i32 当前守护，0 没有
    private const val OFF_STARTED = 20             // u32 守护启动的时间（秒）
    private const val OFF_UID = 24                 // u32 0 root，2000 shell
    private const val OFF_AT = 28                  // u32 最近一次检测（秒）
    private const val OFF_STATE = 32               // u8
    private const val OFF_PING = 34                // u16 0.1 毫秒
    private const val OFF_NEXT = 36                // u16 下一条记录的位置
    private const val OFF_PSS = 40                 // u32 守护自己占的内存（PSS，KB）
    private const val OFF_VERSION = 44             // u32 守护程序的版本
    private const val OFF_APP_PID = 48             // i32 应用进程号（应用写）：守护据此发现应用被强行停止
    const val VERSION = 4                          // 和 guardd.c 的 VERSION 一致：在跑的比这个旧就换成新的
    private const val FLAG_STOP = 1
    private const val FLAG_PAUSED = 2
    private const val FLAG_NOTIFY = 4              // 开了“守护通知”：状态变了守护拉起应用更新通知
    private val STATES = listOf("checking", "online", "offline", "unreachable", "wifi-down", "wifi-off", "other")

    fun dir(context: Context): File? = context.getExternalFilesDir("guard")

    private fun bin(context: Context): File? = dir(context)?.let { File(it, "guard.bin") }

    /** 门户地址里的主机解析成 IPv4（域名要联网解析，放后台线程调），返回（4 字节地址，端口）。 */
    fun portalAddress(portal: String): Pair<ByteArray, Int>? = runCatching {
        val uri = URI(portal.trim())
        val host = uri.host ?: return null
        val addr = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } ?: return null
        addr.address to (if (uri.port > 0) uri.port else 80)
    }.getOrNull()

    /** 改控制区（检测间隔、门户、标志位）；文件不在就新建。只碰控制区，守护写的状态和记录不动。 */
    private fun control(
        context: Context, interval: Int? = null, portal: Pair<ByteArray, Int>? = null, appPid: Int? = null, flags: (Int) -> Int = { it },
    ) {
        val f = bin(context) ?: return
        runCatching {
            RandomAccessFile(f, "rw").use { raf ->
                if (raf.length() < OLD_HEADER || raf.readIntLe(0) != MAGIC) {
                    raf.setLength(0)
                    raf.setLength(SIZE.toLong())
                    raf.writeIntLe(0, MAGIC)
                } else if (raf.length() < SIZE) {
                    // 旧版文件（头部 48 字节）：头部前 48 字节不变（在跑的旧守护照常认得自己），记录区挪了位置，清空重记
                    raf.setLength(SIZE.toLong())
                    raf.seek(OLD_HEADER.toLong())
                    raf.write(ByteArray(SIZE - OLD_HEADER))
                    raf.writeShortLe(OFF_NEXT, 0)
                }
                if (appPid != null) raf.writeIntLe(OFF_APP_PID, appPid)
                if (interval != null) raf.writeShortLe(OFF_INTERVAL, interval.coerceIn(10, 600))
                if (portal != null) {
                    raf.seek(OFF_IP.toLong())
                    raf.write(portal.first)
                    raf.writeShortLe(OFF_PORT, portal.second)
                }
                raf.seek(OFF_FLAGS.toLong())
                val old = raf.read().coerceAtLeast(0)
                raf.seek(OFF_FLAGS.toLong())
                raf.write(flags(old))
            }
        }
    }

    /** 写出 start.sh 和控制区（启动前、ADB 方式给命令前都要先写；应用更新后守护程序的路径会变，所以每次都写）。 */
    fun prepare(context: Context, interval: Int, portal: Pair<ByteArray, Int>?, paused: Boolean, notify: Boolean): File {
        val d = dir(context) ?: throw IllegalStateException("外部存储不可用")
        val bin = File(context.applicationInfo.nativeLibraryDir, "libguardd.so").absolutePath
        File(d, "start.sh").writeText(
            """
            |#!/system/bin/sh
            |# 启动校园网保活守护（root、ADB 都执行这一个：sh start.sh）。
            |# 守护程序在应用的安装目录里；在新会话里后台运行，关掉 adb 也不影响。
            |D="${'$'}{0%/*}"
            |B='$bin'
            |if [ ! -x "${'$'}B" ]; then
            |    echo "找不到守护程序：应用可能更新过，请打开一次应用再执行"
            |    exit 1
            |fi
            |setsid "${'$'}B" "${'$'}D" </dev/null >/dev/null 2>&1 &
            |echo "校园网保活守护已启动"
            |""".trimMargin(),
        )
        for (old in listOf("guard.sh", "status", "pid", "guard.log", "interval", "portal", "paused", "stop")) File(d, old).delete()   // 旧版的文件
        // 顺便清掉停止标志；记下应用进程号
        control(context, interval, portal, android.os.Process.myPid()) { (if (paused) FLAG_PAUSED else 0) or (if (notify) FLAG_NOTIFY else 0) }
        return d
    }

    /** 检测间隔、门户变了就改控制区：守护每次检测前都会重新读。 */
    fun writeConfig(context: Context, interval: Int, portal: Pair<ByteArray, Int>?) = control(context, interval, portal)

    /** 自动重连暂停（手动注销、连续失败）或关闭时，让守护别再拉起应用登录。 */
    fun setPaused(context: Context, paused: Boolean) =
        control(context) { if (paused) it or FLAG_PAUSED else it and FLAG_PAUSED.inv() }

    /** 开关“守护通知”：开着时守护在状态变化时拉起应用更新通知。 */
    fun setNotify(context: Context, on: Boolean) =
        control(context) { if (on) it or FLAG_NOTIFY else it and FLAG_NOTIFY.inv() }

    /** 请求守护马上检测一次（10 秒内）：登录、注销后让守护的状态和通知跟上。 */
    fun requestCheck(context: Context) {
        val f = bin(context)?.takeIf { it.length() >= SIZE } ?: return
        runCatching {
            RandomAccessFile(f, "rw").use { raf ->
                raf.seek(OFF_CHECK.toLong())
                val v = raf.read().coerceAtLeast(0)
                raf.seek(OFF_CHECK.toLong())
                raf.write((v + 1) and 0xff)
            }
        }
    }

    /**
     * 记下应用进程号（进程起来时），自己退出前写 0。守护发现记着的进程不在了，说明应用是被强行停止的
     * （ColorOS 划掉后台就是），通知也被系统一起清掉了，就拉起应用重新显示。文件不在（没用过守护）就不管。
     */
    fun setAppPid(context: Context, pid: Int) {
        val f = bin(context)?.takeIf { it.length() >= SIZE } ?: return
        runCatching {
            RandomAccessFile(f, "rw").use { raf ->
                if (raf.readIntLe(0) == MAGIC) raf.writeIntLe(OFF_APP_PID, pid)
            }
        }
    }

    fun adbCommand(context: Context): String = "adb shell sh ${File(dir(context), "start.sh").absolutePath}"

    /** 用 su 执行一条命令，返回（退出码，输出）；没有 su、被拒绝或超时返回 -1。第一次会弹出 Magisk 授权框。 */
    fun su(command: String, timeoutSec: Long = 20): Pair<Int, String> = try {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }.apply { start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroy()
            -1 to "超时"
        } else {
            reader.join(1000)
            p.exitValue() to out.toString()
        }
    } catch (e: Exception) {
        -1 to (e.message ?: e.toString())
    }

    /** 用 mode 指定的方式启动（已经在跑的会被新的接替）；ADB 方式要用户自己执行命令，这里返回 false。 */
    fun start(context: Context, mode: String): Boolean {
        val start = File(dir(context) ?: return false, "start.sh").absolutePath
        return mode == ROOT && su("sh '$start'").first == 0
    }

    /** 让守护退出：设停止标志，守护 10 秒内就会退出（不用 root）。 */
    fun stop(context: Context) = control(context) { it or FLAG_STOP }

    private fun read(context: Context): ByteBuffer? = runCatching {
        val bytes = bin(context)!!.readBytes()
        if (bytes.size < SIZE) return null
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).takeIf { it.getInt(0) == MAGIC }
    }.getOrNull()

    /** 读守护的状态：有守护（pid 不为 0）而且超过两轮检测间隔还没更新，就当它没在跑（被系统杀了之类）。 */
    fun status(context: Context, interval: Int): GuardStatus {
        val b = read(context) ?: return GuardStatus(false, 0, "", "", false)
        val pid = b.getInt(OFF_PID)
        val at = b.getInt(OFF_AT).toLong() and 0xffffffffL
        val fresh = System.currentTimeMillis() / 1000 - at <= interval.coerceIn(10, 600) * 2 + 30
        val ping = b.getShort(OFF_PING).toInt() and 0xffff
        return GuardStatus(
            running = pid != 0 && fresh,
            at = at,
            state = STATES.getOrElse(b.get(OFF_STATE).toInt() and 0xff) { "" },
            pingMs = if (ping == 0) "" else "%.1f".format(ping / 10.0),
            root = b.getInt(OFF_UID) == 0,
            pssKb = b.getInt(OFF_PSS),
            version = b.getInt(OFF_VERSION),
            started = b.getInt(OFF_STARTED).toLong() and 0xffffffffL,
        )
    }

    private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    private fun ping(arg: Int) = if (arg == 0) "超时" else "%.1f ms".format(arg / 10.0)

    /** 守护的一条事件：时间（秒）、类别（success / info / warning / error）、文字。 */
    data class Event(val at: Long, val kind: String, val text: String, val detail: String = "") {
        val time: String get() = TIME.format(Instant.ofEpochSecond(at))
    }

    /** 把守护的事件记录翻成文字（新的在前）：守护只写事件码，文字都在这里。 */
    fun events(context: Context, limit: Int = 80): List<Event> {
        val b = read(context) ?: return emptyList()
        val next = (b.getShort(OFF_NEXT).toInt() and 0xffff) % RING
        val out = ArrayList<Event>()
        for (k in 1..RING) {
            val i = (next - k + RING) % RING
            val at = b.getInt(HEADER + i * RECORD).toLong() and 0xffffffffL
            if (at == 0L) break
            val arg = b.getShort(HEADER + i * RECORD + 6).toInt() and 0xffff
            val e = when (b.get(HEADER + i * RECORD + 4).toInt()) {
                1, 2, 8, 9 -> continue   // 启动、退出、Wi-Fi 关闭、不是校园网：日常信息，不显示（旧版守护写过）
                3 -> Event(at, "success", "恢复在线", "ping ${ping(arg)}")
                4 -> Event(at, "warning", "不在线", "门户显示未登录")
                5 -> Event(at, "error", "连不上门户", "ping ${ping(arg)}")
                6 -> Event(at, "warning", "Wi-Fi 断开")
                7 -> Event(at, "success", "Wi-Fi 已连上", "断开了 $arg 秒")
                10 -> Event(at, "info", "重新连接 Wi-Fi")
                11 -> Event(at, "info", "拉起应用登录")
                12 -> Event(at, "warning", "重连 Wi-Fi", "连续 3 次连不上门户")
                else -> continue
            }
            out += e
        }
        // 旧版守护每次启动、回到前台都记一条“在线”：前面没有问题的“恢复”不显示
        var issue = false
        val kept = out.asReversed().filter { e ->
            when (e.kind) {
                "warning", "error" -> { issue = true; true }
                "success" -> issue.also { if (e.text == "恢复在线") issue = false }
                else -> true
            }
        }
        return kept.asReversed().take(limit)
    }

    /** 清空事件记录（只清记录区，守护照常往后写）。 */
    fun clearEvents(context: Context) {
        val f = bin(context) ?: return
        runCatching {
            RandomAccessFile(f, "rw").use { raf ->
                if (raf.length() < SIZE) return
                raf.seek(HEADER.toLong())
                raf.write(ByteArray(RING * RECORD))
            }
        }
    }

    private fun RandomAccessFile.readIntLe(off: Int): Int {
        seek(off.toLong())
        val b = ByteArray(4)
        readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun RandomAccessFile.writeIntLe(off: Int, v: Int) {
        seek(off.toLong())
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    }

    private fun RandomAccessFile.writeShortLe(off: Int, v: Int) {
        seek(off.toLong())
        write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
    }
}
