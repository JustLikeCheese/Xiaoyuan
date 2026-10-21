package io.github.justlikecheese.xiaoyuan.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/*
 * 请求日志的保存、轮转和导出，对应桌面端 logfiles.py。
 * 当天的日志写在 logs/portal.log；过了零点（或单个文件超过 MAX_BYTES）就压缩成 logs/portal-日期.log.gz，
 * 压缩包保留 KEEP_DAYS 天，更早的自动删除。
 */

const val KEEP_DAYS = 30
const val MAX_BYTES = 2L * 1024 * 1024
private val ARCHIVE = Regex("^(.+)-(\\d{4}-\\d\\d-\\d\\d)(?:-(\\d+))?\\.log\\.gz$")
private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS")

/** 只放当天日志的文件；换天或文件过大时把旧内容压缩归档。格式同桌面端：“时间 消息”。 */
class ArchivingLog(val path: File, private val keepDays: Int = KEEP_DAYS, private val maxBytes: Long = MAX_BYTES) {
    private var day: LocalDate
    private var out: FileOutputStream? = null

    init {
        path.parentFile?.mkdirs()
        day = contentDay(path)
        // 上次运行留下的旧日志，启动时就归档，不用等到下一次写入
        if (day != LocalDate.now()) rollover()
    }

    @Synchronized
    fun write(message: String) {
        try {
            val now = LocalDateTime.now()
            if (now.toLocalDate() != day || path.length() >= maxBytes) rollover(now.toLocalDate())
            val stream = out ?: FileOutputStream(path, true).also { out = it }
            stream.write("${now.format(STAMP)} $message\n".toByteArray())
            stream.flush()
        } catch (_: IOException) {
            // 写日志失败不影响登录
        }
    }

    /** 把当前文件压缩归档并清掉过期的压缩包。 */
    @Synchronized
    fun rollover(newDay: LocalDate? = null) {
        out?.close()
        out = null
        if (archive(path, day)) day = newDay ?: LocalDate.now()
        path.parentFile?.let { prune(it, keepDays) }
    }

    val dir: File get() = path.parentFile ?: path.absoluteFile.parentFile
}

/** 文件里内容属于哪一天：以最后修改时间为准，空文件或不存在算今天。 */
private fun contentDay(path: File): LocalDate =
    if (path.isFile && path.length() > 0) dayOf(path.lastModified()) else LocalDate.now()

private fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

fun archiveName(path: File, day: LocalDate, seq: Int = 1): File {
    val stem = path.name.removeSuffix(".log")
    return File(path.parentFile, "$stem-$day${if (seq == 1) "" else "-$seq"}.log.gz")
}

/**
 * 把 src 压缩成 destDir/名字-日期.log.gz 并删除 src。src 不存在或为空时什么都不做，返回 true。
 * 先把 src 改名再压缩，压缩失败就放回去，日志不丢。
 */
fun archive(src: File, day: LocalDate, destDir: File? = null, destName: String? = null): Boolean {
    if (!src.isFile || src.length() == 0L) return true
    val dir = destDir ?: src.parentFile!!
    dir.mkdirs()
    val claimed = File(src.parentFile, src.name + ".archiving")
    if (!src.renameTo(claimed)) return false
    var seq = 1
    var target = archiveName(File(dir, destName ?: src.name), day, seq)
    while (target.exists()) target = archiveName(File(dir, destName ?: src.name), day, ++seq)
    val tmp = File(dir, target.name + ".tmp")
    try {
        claimed.inputStream().use { input -> GZIPOutputStream(tmp.outputStream()).use { input.copyTo(it) } }
        if (!tmp.renameTo(target)) throw IOException("改名失败")
    } catch (_: IOException) {
        tmp.delete()
        claimed.renameTo(src)
        return false
    }
    claimed.delete()
    return true
}

data class ArchiveFile(val day: LocalDate, val seq: Int, val file: File)

/** 目录下的压缩日志，按日期和序号从旧到新排列。 */
fun archives(logDir: File): List<ArchiveFile> =
    (logDir.listFiles() ?: emptyArray()).mapNotNull { f ->
        val m = ARCHIVE.matchEntire(f.name) ?: return@mapNotNull null
        val day = runCatching { LocalDate.parse(m.groupValues[2]) }.getOrNull() ?: return@mapNotNull null
        ArchiveFile(day, m.groupValues[3].toIntOrNull() ?: 1, f)
    }.sortedWith(compareBy({ it.day }, { it.seq }, { it.file.name }))

fun prune(logDir: File, keepDays: Int) {
    val oldest = LocalDate.now().minusDays(keepDays.toLong())
    for (a in archives(logDir)) if (a.day < oldest) a.file.delete()
}

data class LogSummary(val files: Int, val size: Long, val since: String)

/** 日志占用：文件数、字节数、最早的日期。 */
fun summary(logDir: File, current: File): LogSummary {
    val olds = archives(logDir)
    val files = olds.map { it.file } + listOfNotNull(current.takeIf { it.exists() })
    return LogSummary(files.size, files.sumOf { it.length() }, olds.firstOrNull()?.day?.toString() ?: "")
}

/**
 * 把全部日志（压缩的先解压）按时间顺序合并写进 out，返回写入的字节数。
 * transform(行) -> 行：导出前逐行处理（比如打码）；为 null 时原样复制。
 */
fun export(logDir: File, current: File, out: OutputStream, transform: ((String) -> String)? = null): Long {
    var written = 0L
    for (a in archives(logDir)) {
        try {
            GZIPInputStream(a.file.inputStream()).use { written += copy(it, out, transform) }
        } catch (_: IOException) {
            continue   // 损坏的压缩包跳过，不影响其他日志
        }
    }
    if (current.exists()) current.inputStream().use { written += copy(it, out, transform) }
    out.flush()
    return written
}

private fun copy(src: InputStream, dst: OutputStream, transform: ((String) -> String)?): Long {
    if (transform == null) return src.copyTo(dst, 256 * 1024)
    var n = 0L
    src.bufferedReader().lineSequence().forEach { line ->
        val bytes = (transform(line) + "\n").toByteArray()
        dst.write(bytes)
        n += bytes.size
    }
    return n
}
