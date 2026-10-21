package io.github.justlikecheese.xiaoyuan.portal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/*
 * Dr.COM 校园网门户协议，逐段对照桌面端 portal.py 移植，行为保持一致（说明见那边的文件头）。不依赖 Android。
 *
 * - 网页加载时先调 /eportal/portal/page/loadConfig，登录、注销的参数都取自门户配置，读不到时用本校实测的值。
 * - 本校 login_method=1：登录、注销走 801 端口的 /eportal/portal/login、logout，不能用 /drcom/login（显示在线却没网）。
 * - 注销：门户开了 un_bind_mac 且 register_mode 为 1/3/4 时先调 mac/unbind，失败再 logout。
 * - 在线状态查 /drcom/chkstatus；另有账号信息（page/loadUserInfo）和门户公告（notice/get_notice_content）。
 * - 门户限制请求频率（约 5 秒一次）：登录、注销经过全局闸门 RequestGate，门户说太频繁时退避后重发。
 *   登录、注销要带的本机 IP/MAC/VLAN 优先用最近一次查状态的结果，切换账号只有“注销 → 登录”两次请求。
 */

const val DEFAULT_HOST = "10.253.0.1"
const val SELF_SERVICE_URL = "http://10.255.255.3:8080/Self"
const val JS_VERSION = "4.2.1"
private const val UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
private const val UA_PHONE = "Mozilla/5.0 (Linux; Android 12; K) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
private const val UA_TABLET = "Mozilla/5.0 (Linux; Android 12; K) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/** 终端类型，对应页面 util.getTermType()；登录时作为 terminal_type 发送，也决定账号前缀。 */
val TERMINAL_TYPES = listOf("电脑" to 1, "手机" to 2, "平板（横屏）" to 3, "平板（竖屏）" to 4, "其他" to 0)
const val DEFAULT_TERMINAL_TYPE = 1

/** 门户配置 account_prefix=1 时页面给账号加的前缀：手机 ",1,"，其他终端 ",0,"（a40.js login.init）。 */
fun accountPrefix(cfgPrefix: Int, terminalType: Int): String = when {
    cfgPrefix != 1 -> ""
    terminalType == 2 -> ",1,"
    else -> ",0,"
}

const val CONFIG_TTL = 600.0        // page/loadConfig 的结果缓存这么多秒
const val NO_MAC = "000000000000"
const val MAX_BACKOFF = 60.0        // 门户说太频繁时，退避的上限（秒）
const val RATE_RETRIES = 2          // 门户说太频繁时自动重发的次数
const val TERMINAL_TTL = 120.0      // 查状态得到的本机 IP/MAC/VLAN 在这么多秒内直接复用
const val UNLIMITED = 4294967295L   // oltime / olflow 为 32 位最大值时表示不限

/** 运营商：后缀直接拼在账号后面，取值与门户页 ISP_select 下拉框一致。 */
val CARRIERS = listOf("校园网" to "", "中国联通" to "@unicom", "中国电信" to "@telecom", "中国移动" to "@cmcc")

fun carrierName(suffix: String): String = CARRIERS.firstOrNull { it.second == suffix }?.first ?: CARRIERS[0].first

internal fun monotonic(): Double = System.nanoTime() / 1e9

/** 请求日志：每次请求和门户的原始返回（密码已打码）。sink 由界面那边接到日志文件上。 */
object PortalLog {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun info(message: String) {
        sink?.invoke(message)
    }
}

// ---------------------------------------------------------------- 请求频率

/**
 * 同一门户的认证请求排队发出：两次之间至少隔 interval 秒；门户说太频繁时把间隔加倍退避，成功后恢复。
 * 线程安全；等待发生在调用者的线程里（后台线程），等待前回调 waitListener(秒数)，界面据此显示倒计时。
 */
class RequestGate(interval: Double? = null) {
    @Volatile var interval: Double = interval ?: PortalState.currentAuthInterval   // 运行时再读，setAuthInterval 之后新建的也生效
    @Volatile var backoff = 0.0
    @Volatile internal var next = 0.0
    private val lock = Any()

    /**
     * 等到可以发下一个请求，返回等了多少秒。持锁等待：多个请求按先来后到依次发出。
     * 线程被中断（界面取消了这次登录 / 注销）时抛 InterruptedException，请求不会发出，也不占用间隔。
     */
    fun waitTurn(): Double = synchronized(lock) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val delay = next - monotonic()
        if (delay > 0) {
            PortalState.waitListener?.invoke(delay)
            Thread.sleep((delay * 1000).toLong())
        }
        next = monotonic() + maxOf(interval, backoff)
        PortalState.sendListener?.invoke()
        maxOf(delay, 0.0)
    }

    /** 门户说太频繁：加长间隔，返回新的间隔。 */
    fun throttled(): Double = synchronized(lock) {
        backoff = minOf(MAX_BACKOFF, maxOf(interval, 1.0, backoff) * 2)
        next = monotonic() + backoff
        backoff
    }

    fun succeeded() {
        backoff = 0.0
    }

    fun remaining(): Double = maxOf(0.0, next - monotonic())

    /** 刚发的请求被门户拒了、什么也没改（比如解绑 MAC 说“mac不存在”）：不占间隔，下一个请求马上可以发。 */
    fun refund() {
        next = monotonic()
    }
}

/** 门户眼里本机的 IP、MAC、VLAN 和在线账号（不在线为空），at 为查到的时间。 */
data class TerminalInfo(val at: Double, val ip: String, val mac: String, val vlan: String, val account: String)

/** 各门户共用的状态：频率闸门、本机信息缓存、门户配置缓存（对应 portal.py 的模块级变量）。 */
object PortalState {
    val gates = ConcurrentHashMap<String, RequestGate>()
    val terminals = ConcurrentHashMap<String, TerminalInfo>()
    val configs = ConcurrentHashMap<String, Pair<Double, PortalConfig>>()
    @Volatile var currentAuthInterval = 5.0   // 登录、注销请求之间的最短间隔（秒）
    @Volatile var waitListener: ((Double) -> Unit)? = null
    @Volatile var sendListener: (() -> Unit)? = null   // 认证请求排到了、马上发出（在发请求的线程里调）
    @Volatile var doneListener: ((Boolean) -> Unit)? = null   // 认证请求有了回复（门户是否说成功），或者出错
    // 解绑 MAC 时门户说“mac不存在”的（门户 -> MAC）：本机没绑 MAC，之后注销直接走普通注销，少一个请求
    val noMacBinding = ConcurrentHashMap<String, String>()

    fun gateFor(host: String): RequestGate = gates.computeIfAbsent(host) { RequestGate() }

    /** 改所有门户的认证请求最短间隔（测试里用来加速）。 */
    fun setAuthInterval(seconds: Double) {
        currentAuthInterval = seconds
        for (g in gates.values) {
            g.interval = seconds
            g.backoff = 0.0
            g.next = 0.0
        }
    }

    /** 网络变了（换了 Wi-Fi、重新拿到 IP），下次登录前重新查本机 IP/MAC 和门户配置。 */
    fun forgetTerminal() {
        terminals.clear()
        configs.clear()
        noMacBinding.clear()
    }
}

/** 门户的回复是不是在说“太频繁 / 繁忙，稍后再试”。 */
fun looksRateLimited(d: JsonObject): Boolean {
    if (d.str("ret_code").toIntOrNull() == 3) return true   // 系统繁忙
    val msg = decodeMsg(d.str("msg")).lowercase()
    return listOf("频繁", "过快", "too many", "too frequent", "frequently", "rate limit", "busy").any { it in msg }
}

/** 连不上门户或门户返回异常。 */
open class PortalException(message: String, val httpStatus: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

/** 重发几次后门户仍然说太频繁。 */
class RateLimitedException(message: String) : PortalException(message)

/** 门户明确拒绝了登录，消息是给用户看的原因；code 为门户的 ret_code。 */
class LoginException(message: String, val code: Int? = null) : Exception(message)

data class Status(
    val online: Boolean,
    val account: String = "",
    val ip: String = "",
    val minutes: Long = 0,
    val flowKb: Long = 0,
    val mac: String = NO_MAC,
    val vlan: String = "0",
    val raw: JsonObject = JsonObject(emptyMap()),   // chkstatus 的全部原始字段
)

/** page/loadConfig 里和认证有关的几项，取值方式同 a41.js page.getPageInfo（parseInt(x) || 0）。 */
data class PortalConfig(
    val loginMethod: Int = 1,       // 认证方式：1/9 Portal 协议（本校为 1）
    val accountPrefix: Int = 1,     // 1：账号前加 ",0,"（电脑端）
    val acLogout: Int = 0,          // 多 AC 注销方式：0 默认 1 通过 Radius 2 通过全业务接口
    val registerMode: Int = 1,      // 后台类型：0 私有云 1 BS 后台 2 酒店版 3 访客系统 4 普教系统
    val unBindMac: Int = 0,         // 1：本机注销时解绑 MAC
    val userInfo: String = "",      // 成功页要显示的账号信息字段，逗号分隔，空表示门户没配置
    val programIndex: String = "",  // 方案索引、页面索引：查账号信息时要带
    val pageIndex: String = "",
    val loaded: Boolean = false,    // false 表示没读到门户配置，用的是默认值
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    /** 对应页面 wc()：un_bind_mac==1 且 register_mode 为 1/3/4 时，注销走 mac/unbind。 */
    val unbindOnLogout: Boolean get() = unBindMac == 1 && registerMode in setOf(1, 3, 4)

    companion object {
        fun fromJson(data: JsonObject): PortalConfig {
            fun num(key: String) = data[key].text().trim().toDoubleOrNull()?.toInt() ?: 0
            fun text(key: String) = data[key].text().trim()
            return PortalConfig(
                num("login_method"), num("account_prefix"), num("ac_logout"), num("register_mode"),
                num("un_bind_mac"), text("user_info"), text("program_index"), text("page_index"), true, data,
            )
        }
    }
}

val DEFAULT_CONFIG = PortalConfig()   // 读不到 loadConfig 时用本校实测的值

/** 页面 util.ipToParseInt：点分 IP 转成十进制整数（mac/unbind 用）。 */
fun ipToInt(ip: String): String {
    val parts = ip.split(".").map { it.trim().toIntOrNull() ?: return "0" }
    if (parts.size != 4) return "0"
    val (a, b, c, d) = parts
    return (((a.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong()) and 0xFFFFFFFFL).toString()
}

/** page/loadUserInfo 的结果。fields 为 (字段名, 标题, 值)，顺序同门户配置的 user_info。 */
data class UserInfo(
    val fields: List<Triple<String, String, String>> = emptyList(),
    val progress: Double? = null,   // flow_progress：已用百分比（0~100），门户没给时为 null
    val progressType: Int = 0,      // 1 按流量 2 按时长 其他 不限
    val used: String = "",          // 已用（流量或时长，门户格式化好的文字）
    val available: String = "",     // 可用
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** 门户没给字段标题（user_info_lang）时用的常见字段名。 */
val USER_INFO_TITLES = mapOf(
    "account" to "账号", "user_name" to "姓名", "username" to "姓名", "real_name" to "姓名",
    "use_flow" to "已用流量", "available_flow" to "可用流量", "use_time" to "已用时长", "available_time" to "可用时长",
    "balance" to "余额", "fee" to "余额", "package_name" to "套餐", "service_name" to "套餐", "group_name" to "用户组",
    "expire_time" to "到期时间", "end_time" to "到期时间", "online_num" to "在线设备",
)
private val USER_INFO_SKIP = setOf("flow_progress", "progress_type")

fun stripHtml(text: String): String {
    var t = Regex("(?i)<br\\s*/?>|</p>|</div>").replace(text, "\n")
    t = Regex("<[^>]+>").replace(t, "")
    return Regex("\\n\\s*\\n+").replace(htmlUnescape(t), "\n").trim()
}

private val ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "hellip" to "…", "mdash" to "—",
    "ndash" to "–", "middot" to "·", "copy" to "©", "times" to "×",
)

internal fun htmlUnescape(text: String): String =
    Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);").replace(text) { m ->
        val name = m.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
            else -> ENTITIES[name]
        } ?: m.value
    }

private fun codePoint(cp: Int): String? = if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null

class Portal(
    base: String? = null,
    val timeout: Double = 8.0,
    terminalType: Int = DEFAULT_TERMINAL_TYPE,
    private val transport: Transport = UrlTransport(),
) {
    val host: String
    val drcomUrl: String
    val eportalUrl: String
    val terminalType: Int = if (TERMINAL_TYPES.any { it.second == terminalType }) terminalType else DEFAULT_TERMINAL_TYPE
    val gate: RequestGate

    /**
     * base 为门户根地址，默认 http://10.253.0.1。
     * 真实门户的 eportal 接口在 801 端口；base 自带端口时（比如假门户）两类接口共用同一个地址。
     */
    init {
        var b = (base?.takeIf { it.isNotEmpty() } ?: System.getenv("XIAOYUAN_PORTAL")?.takeIf { it.isNotEmpty() }
            ?: "http://$DEFAULT_HOST").trim().trimEnd('/')
        if ("://" !in b) b = "http://$b"
        val uri = runCatching { URI(b) }.getOrNull()
        host = uri?.host?.lowercase() ?: DEFAULT_HOST
        val eportalBase = if (uri != null && uri.port != -1) b else "${uri?.scheme ?: "http"}://$host:801"
        drcomUrl = "$b/drcom/"
        eportalUrl = "$eportalBase/eportal/portal/"
        gate = PortalState.gateFor(host)
    }

    private val userAgent = when (this.terminalType) {
        2 -> UA_PHONE
        3, 4 -> UA_TABLET
        else -> UA_DESKTOP
    }

    // ---- 接口 ----

    fun status(): Status {
        val d = call(drcomUrl + "chkstatus", "dr1002")
        val mac = d.str("ss4").replace("-", "").replace(":", "")
        val st = Status(
            online = ok(d),
            account = d.str("uid"),
            ip = d.str("v46ip").ifEmpty { d.str("ss5") }.ifEmpty { d.str("v4ip") },
            minutes = d.num("time"),
            flowKb = d.num("flow"),
            mac = if (mac.isNotEmpty() && mac != NO_MAC && mac != "111111111111") mac else NO_MAC,
            vlan = d.str("vid").ifEmpty { "0" },
            raw = d,
        )
        if (st.ip.isNotEmpty()) {
            PortalState.terminals[host] = TerminalInfo(monotonic(), st.ip, st.mac, st.vlan, if (st.online) st.account else "")
        }
        return st
    }

    /** 门户配置（page/loadConfig），缓存 CONFIG_TTL 秒；读不到时返回 DEFAULT_CONFIG。参数同 a41.js getPageInfo。 */
    fun config(fresh: Boolean = false): PortalConfig {
        val cached = PortalState.configs[host]
        if (cached != null && !fresh && monotonic() - cached.first < CONFIG_TTL) return cached.second
        val t = terminal()
        val d = try {
            call(eportalUrl + "page/loadConfig", "dr1001", listOf(
                "program_index" to "",
                "wlan_vlan_id" to t.vlan,
                "wlan_user_ip" to Base64.getEncoder().encodeToString(t.ip.toByteArray()),
                "wlan_user_ipv6" to "",
                "wlan_user_ssid" to "",
                "wlan_user_areaid" to "",
                "wlan_ac_ip" to "",
                "wlan_ap_mac" to NO_MAC,
                "gw_id" to NO_MAC,
            ))
        } catch (e: PortalException) {
            PortalLog.info("读取门户配置失败（${e.message}），使用默认配置")
            return DEFAULT_CONFIG
        }
        val data = d["data"] as? JsonObject
        if (data == null) {
            PortalLog.info("门户配置格式不对，使用默认配置")
            return DEFAULT_CONFIG
        }
        val cfg = PortalConfig.fromJson(data)
        PortalLog.info("门户配置：login_method=${cfg.loginMethod} account_prefix=${cfg.accountPrefix} " +
            "ac_logout=${cfg.acLogout} register_mode=${cfg.registerMode} un_bind_mac=${cfg.unBindMac}")
        PortalState.configs[host] = monotonic() to cfg
        return cfg
    }

    /**
     * 登录，失败抛 LoginException。account 为账号，suffix 为运营商后缀。
     * 刚注销时 AC 还没释放旧会话，会返回“终端 IP 已经在线”（ret_code=2）；wait 秒内遇到这种情况就重试，
     * 重试同样受请求间隔限制（默认 5 秒一次）。
     */
    fun login(account: String, password: String, suffix: String = "", wait: Double = 0.0) {
        val deadline = monotonic() + wait
        var refreshed = false
        while (true) {
            try {
                loginOnce(account, password, suffix)
                return
            } catch (e: LoginException) {
                if (e.code == 998 && !refreshed) {
                    // 参数不全：多半是复用的本机 IP 已经过时，重新查一次再试
                    refreshed = true
                    PortalState.forgetTerminal()
                    continue
                }
                if (e.code != 2 || monotonic() + gate.remaining() >= deadline) throw e
                PortalLog.info("AC 还没释放旧会话，稍后重试")
                if (gate.remaining() == 0.0) {   // 有请求间隔时由闸门负责等待，没有时自己隔 1 秒
                    Thread.sleep((minOf(1.0, maxOf(0.0, deadline - monotonic())) * 1000).toLong())
                }
            }
        }
    }

    /** 对应页面 login.login_portal（login_method 1/9）。 */
    private fun loginOnce(account: String, password: String, suffix: String) {
        val cfg = config()
        val t = terminal()
        val prefix = accountPrefix(cfg.accountPrefix, terminalType)
        val d = authCall(eportalUrl + "login", "dr1003", listOf(
            "login_method" to loginMethod(cfg).toString(),
            "user_account" to prefix + account + suffix,
            "user_password" to password,
            "wlan_user_ip" to t.ip,
            "wlan_user_ipv6" to "",
            "wlan_user_mac" to t.mac,
            "wlan_ac_ip" to "",
            "wlan_ac_name" to "",
            "jsVersion" to JS_VERSION,
            "terminal_type" to terminalType.toString(),
            "lang" to "zh-cn",
        ))
        if (!ok(d)) throw LoginException(describeLoginError(d, t.ip, t.mac), d.str("ret_code").toIntOrNull())
    }

    /**
     * 注销，返回门户是否确认成功（本来就不在线时可能返回 false）。对应页面 wc()。
     * 门户要求注销时解绑 MAC 的，先调 mac/unbind（user.unbind_mac），成功就算注销完成；
     * 失败或不需要解绑时调 logout（logout.logout_portal），ac_logout、register_mode 用门户配置里的值。
     */
    fun logout(): Boolean {
        val cfg = config()
        var t = terminal()
        if (cfg.unbindOnLogout && PortalState.noMacBinding[host] != t.mac) {
            if (t.account.isEmpty()) {
                // 解绑要带在线账号；缓存里没有（或缓存时不在线）就重新查一次
                val st = freshStatus()
                t = TerminalInfo(t.at, st.ip.ifEmpty { t.ip }, st.mac, st.vlan, if (st.online) st.account else "")
            }
            if (t.account.isNotEmpty() && unbindMac(t.account, t.mac, t.ip)) return true
        }
        val d = authCall(eportalUrl + "logout", "dr1004", listOf(
            "login_method" to loginMethod(cfg).toString(),
            "user_account" to "drcom",
            "user_password" to "123",
            "ac_logout" to cfg.acLogout.toString(),
            "register_mode" to cfg.registerMode.toString(),
            "wlan_user_ip" to t.ip,
            "wlan_user_ipv6" to "",
            "wlan_vlan_id" to t.vlan,
            "wlan_user_mac" to t.mac,
            "wlan_ac_ip" to "",
            "wlan_ac_name" to "",
            "jsVersion" to JS_VERSION,
        ))
        return ok(d)
    }

    /** 对应页面 user.unbind_mac('', '', 1)：注销并解绑本机 MAC。 */
    private fun unbindMac(account: String, mac: String, ip: String): Boolean {
        val d = authCall(eportalUrl + "mac/unbind", "dr1006", listOf(
            "user_account" to account.trim(),
            "wlan_user_mac" to mac.trim().uppercase(),
            "wlan_user_ip" to ipToInt(ip.trim()),
            "jsVersion" to JS_VERSION,
        ))
        if (ok(d)) return true
        val msg = d.str("msg")
        PortalLog.info("解绑 MAC 失败（${msg.ifEmpty { "门户没给原因" }}），改用普通注销")
        gate.refund()   // 解绑没成功、什么也没改：紧接着注销不用再等间隔（网页也是连着发的）
        if ("不存在" in msg) PortalState.noMacBinding[host] = mac
        return false
    }

    /** 只实现了网页里的 Portal 协议分支（1/9）；门户报了别的方式时仍按本校实测的 1 处理，并记日志。 */
    private fun loginMethod(cfg: PortalConfig): Int {
        if (cfg.loginMethod == 1 || cfg.loginMethod == 9) return cfg.loginMethod
        PortalLog.info("门户配置的认证方式 login_method=${cfg.loginMethod} 未实现，按 1 处理")
        return 1
    }

    /** 账号信息（page/loadUserInfo），对应成功页 editInfo.query_user_info。不在线或门户不支持时返回 null。 */
    fun userInfo(): UserInfo? {
        val cfg = config()
        val t0 = terminal()
        var ip = t0.ip
        var mac = t0.mac
        var account = t0.account
        if (account.isEmpty()) {
            val st = freshStatus()
            ip = st.ip.ifEmpty { ip }
            mac = st.mac
            account = if (st.online) st.account else ""
        }
        if (account.isEmpty()) return null
        val d = call(eportalUrl + "page/loadUserInfo", "dr1007", listOf(
            "lang" to "zh-cn",
            "program_index" to cfg.programIndex,
            "page_index" to cfg.pageIndex,
            "user_account" to account.trim(),
            "wlan_user_ip" to ipToInt(ip),
            "wlan_user_mac" to mac,
        ))
        val data = d["user_info"] as? JsonObject
        if (!(d.str("code") == "1" || ok(d)) || data == null) return null
        val titles = d["user_info_lang"] as? JsonObject ?: JsonObject(emptyMap())
        val keys = cfg.userInfo.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { data.keys.toList() }
        val fields = keys.filter { it !in USER_INFO_SKIP && it in data }.map { k ->
            val title = titles[k].text().ifEmpty { USER_INFO_TITLES[k] ?: k }
            Triple(k, title, data[k].text().trim())
        }
        val progress = if ("flow_progress" in data) data["flow_progress"].text().trim().toDoubleOrNull()
            ?.coerceIn(0.0, 100.0) else null
        val ptype = data.num("progress_type").toInt()
        val (used, available) = when (ptype) {
            1 -> data.str("use_flow") to data.str("available_flow")
            2 -> data.str("use_time") to data.str("available_time")
            else -> "" to ""
        }
        return UserInfo(fields, progress, ptype, used, available, data)
    }

    /** 门户公告（notice/get_notice_content），去掉 HTML 标签；没有公告返回空串。 */
    fun notice(): String {
        val d = call(eportalUrl + "notice/get_notice_content", "dr1008")
        return if (ok(d)) stripHtml(d.str("content")) else ""
    }

    /** 先注销当前账号再登录新账号。两次请求之间本来就要隔 AUTH_INTERVAL 秒，一般正好够 AC 释放旧会话。 */
    fun switch(account: String, password: String, suffix: String = "", wait: Double = 8.0) {
        logout()
        login(account, password, suffix, wait)
    }

    /**
     * 门户眼里本机的 IP、MAC、VLAN 和在线账号（不在线为空）。页面也是从 chkstatus 和页面变量里取的同一批值。
     * 最近 TERMINAL_TTL 秒内查过状态就直接用那次的结果，少发一个请求。
     */
    private fun terminal(): TerminalInfo {
        val cached = PortalState.terminals[host]
        if (cached != null && monotonic() - cached.at < TERMINAL_TTL) return cached
        val st = freshStatus()
        return TerminalInfo(monotonic(), st.ip.ifEmpty { transport.localIp(host) }, st.mac, st.vlan,
            if (st.online) st.account else "")
    }

    private fun freshStatus(): Status = try {
        status()
    } catch (_: PortalException) {
        Status(false)
    }

    // ---- 错误信息，对应 a40.js 中 error.portal_login_err ----

    private fun describeLoginError(d: JsonObject, ip: String, mac: String): String {
        val msg = decodeMsg(d.str("msg"))
        val code = d.str("ret_code").toIntOrNull()
        val fixed = mapOf(
            2 to "本机 IP 已经在线（可能刚注销还没生效，稍等几秒再试）",
            3 to "系统繁忙，请稍后再试",
            5 to "REQ_CHALLENGE 失败，请联系网络中心",
            6 to "REQ_CHALLENGE 超时，请联系网络中心",
            7 to "Radius 认证失败",
            8 to "Radius 认证超时",
            9 to "Radius 下线失败",
            10 to "Radius 下线超时",
            11 to "发生其他错误，请稍后再试",
            998 to "Portal 协议参数不全，请稍后再试",
        )
        fixed[code]?.let { return it }
        if (code == 1 || code == 4 || code == null) {
            if (msg.isNotEmpty()) return explain(msg)
            return lookup("err_code", listOf("ip" to ip, "mac" to mac)).ifEmpty { "AC 认证失败" }
        }
        return msg.ifEmpty { "AC 认证失败" }
    }

    /** 错误代码先问门户要中文提示，问不到再用本地的常见解释。 */
    private fun explain(code: String): String {
        if ("Mac, IP, NASip, PORT err(6)" in code || code.startsWith("error bind vid")) {
            return "首次登录需要先修改密码，请到自助服务平台（$SELF_SERVICE_URL）修改后再登录"
        }
        val prompt = lookup("err_code/loadErrorPrompt", listOf("error_code" to code))
        if (prompt.isNotEmpty() && prompt != code) return prompt
        val hint = hint(code)
        return if (hint != null) "$hint（$code）" else code
    }

    private fun lookup(path: String, args: List<Pair<String, String>>): String {
        val d = try {
            call(eportalUrl + path, "dr1005", args)
        } catch (_: PortalException) {
            return ""
        }
        return if (d.str("result") == "1") d.str("error_prompt_zh") else ""
    }

    // ---- 传输 ----

    /** 登录、注销：先过频率闸门；门户说太频繁就退避后重发，重发几次还不行就报错。 */
    private fun authCall(url: String, callback: String, args: List<Pair<String, String>>): JsonObject {
        for (attempt in 0..RATE_RETRIES) {
            gate.waitTurn()
            val d = try {
                call(url, callback, args).also { PortalState.doneListener?.invoke(ok(it)) }
            } catch (e: PortalException) {
                PortalState.doneListener?.invoke(false)
                if (e.httpStatus in setOf(429, 503) && attempt < RATE_RETRIES) {
                    PortalLog.info("门户返回 HTTP ${e.httpStatus}，${fmt0(gate.throttled())} 秒后重发")
                    continue
                }
                throw e
            }
            if (ok(d) || !looksRateLimited(d)) {
                gate.succeeded()
                return d
            }
            if (attempt == RATE_RETRIES) break
            PortalLog.info("门户说请求太频繁，${fmt0(gate.throttled())} 秒后重发")
        }
        throw RateLimitedException("门户限制了请求频率，请过一会儿再试")
    }

    private fun call(url: String, callback: String, args: List<Pair<String, String>> = emptyList()): JsonObject {
        // 参数顺序与网页 util._jsonp 一致：callback 在最前；没带 jsVersion 时补上；末尾是随机数和 lang
        val query = mutableListOf("callback" to callback)
        query += args
        if (args.none { it.first == "jsVersion" }) query += "jsVersion" to JS_VERSION
        query += "v" to Random.nextInt(500, 10500).toString()
        query += "lang" to "zh"
        val full = url + "?" + query.joinToString("&") { (k, v) -> quote(k) + "=" + quote(v) }
        PortalLog.info("GET ${redact(full)}")
        val result = try {
            transport.get(full, (timeout * 1000).toInt(), mapOf("User-Agent" to userAgent))
        } catch (e: SocketTimeoutException) {
            PortalLog.info("<- 超时")
            throw PortalException("连接门户超时。请确认已连上校园网（HNIU），路由器卡的话稍后再试", cause = e)
        } catch (e: Exception) {
            PortalLog.info("<- $e")
            if (e is ConnectException || e is NoRouteToHostException || e is UnknownHostException ||
                e is PortUnreachableException) {
                throw PortalException("连不上校园网门户，请确认已连上校园网（HNIU）", cause = e)
            }
            throw PortalException("网络错误：${e.message ?: e}", cause = e)
        }
        if (result.code >= 400) {
            PortalLog.info("<- HTTP ${result.code}")
            throw PortalException("门户返回错误 HTTP ${result.code}", httpStatus = result.code)
        }
        // /drcom/ 接口返回 GBK，/eportal/ 接口返回 UTF-8，以响应头为准
        val charset = runCatching { Charset.forName(result.charset ?: "utf-8") }.getOrDefault(Charsets.UTF_8)
        val text = String(result.body, charset)
        PortalLog.info("<- ${text.trim().take(2000)}")
        return parseJsonp(text)
    }
}

private val JSONP = Regex("\\s*[\\w$.]+\\s*\\((.*)\\)\\s*;?\\s*", RegexOption.DOT_MATCHES_ALL)

fun parseJsonp(text: String): JsonObject {
    val m = JSONP.matchEntire(text) ?: throw PortalException("门户返回了无法识别的内容")
    val obj = try {
        Json.parseToJsonElement(m.groupValues[1])
    } catch (e: Exception) {
        throw PortalException("门户返回了无法识别的内容", cause = e)
    }
    return obj as? JsonObject ?: throw PortalException("门户返回了无法识别的内容")
}

fun redact(url: String): String = Regex("((?:user_password|upass)=)[^&]*").replace(url, "$1***")

/** 有的 Dr.COM 版本把错误代码做了 Base64，能解成可读 ASCII 就用解码后的。 */
fun decodeMsg(msg: String): String {
    if (msg.isEmpty() || msg.length % 4 != 0 || !Regex("[A-Za-z0-9+/]+=*").matches(msg)) return msg
    val bytes = try {
        Base64.getDecoder().decode(msg)
    } catch (_: IllegalArgumentException) {
        return msg
    }
    if (bytes.any { it < 0x20 || it > 0x7e }) return msg   // 不是可打印的 ASCII
    val decoded = String(bytes, Charsets.US_ASCII)
    return if (decoded.isNotBlank()) decoded else msg
}

private fun hint(code: String): String? {
    val c = code.lowercase()
    return when {
        c.startsWith("userid error1") -> "账号不存在"
        c.startsWith("userid error2") -> "密码错误"
        "ldap auth error" in c -> "账号或密码错误"
        c.startsWith("in use") -> "账号已在其他设备在线，在线数已满"
        c.startsWith("rad:") -> "运营商认证失败，请检查运营商是否选对、套餐是否有效"
        else -> null
    }
}

/** 同 Python 的 urllib.parse.quote(s, safe='')：只保留字母数字和 _.-~，其余按 UTF-8 百分号编码。 */
internal fun quote(s: String): String {
    val hex = "0123456789ABCDEF"
    val sb = StringBuilder()
    for (byte in s.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xFF
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '.' || ch == '-' || ch == '~') {
            sb.append(ch)
        } else {
            sb.append('%').append(hex[c shr 4]).append(hex[c and 15])
        }
    }
    return sb.toString()
}

/** JSON 值按 Python 的 str() 转成文字：null 为空串，字符串、数字原样。 */
fun JsonElement?.text(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> content
    else -> toString()
}

fun JsonObject.str(key: String): String = this[key].text().trim()

fun JsonObject.num(key: String): Long = str(key).toDoubleOrNull()?.toLong() ?: 0L

private fun ok(d: JsonObject): Boolean {
    val r = d.str("result")
    return r == "1" || r.lowercase() == "ok"
}

private fun fmt0(v: Double) = String.format(Locale.ROOT, "%.0f", v)

// ---------------------------------------------------------------- 格式化

fun formatMinutes(minutes: Long): String =
    if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时 ${minutes % 60} 分"

/** 门户返回的流量单位是 KB。 */
fun formatFlow(kb: Long): String = when {
    kb < 1024 -> "$kb KB"
    kb < 1024 * 1024 -> String.format(Locale.ROOT, "%.2f MB", kb / 1024.0)
    else -> String.format(Locale.ROOT, "%.2f GB", kb / 1024.0 / 1024.0)
}

fun formatSeconds(seconds: Long): String = when {
    seconds < 60 -> "$seconds 秒"
    seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
    else -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分"
}

/** aa74c07503c6 -> AA:74:C0:75:03:C6；全 0 表示门户没拿到，返回空串。 */
fun formatMac(mac: String): String {
    val m = mac.replace("-", "").replace(":", "").uppercase()
    if (m.length != 12 || m.trim('0').isEmpty()) return ""
    return m.chunked(2).joinToString(":")
}

/** 与门户页面一致：fee 去掉末两位后除以 10000 为元。 */
fun formatFee(fee: Long): String = String.format(Locale.ROOT, "%.2f 元", (fee - Math.floorMod(fee, 100L)) / 10000.0)

fun formatLimit(value: Long, fmt: (Long) -> String): String = if (value >= UNLIMITED) "不限" else fmt(value)
