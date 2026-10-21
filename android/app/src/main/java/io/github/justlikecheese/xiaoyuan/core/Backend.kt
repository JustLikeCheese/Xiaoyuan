package io.github.justlikecheese.xiaoyuan.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import io.github.justlikecheese.xiaoyuan.portal.CARRIERS
import io.github.justlikecheese.xiaoyuan.portal.DEFAULT_HOST
import io.github.justlikecheese.xiaoyuan.portal.DEFAULT_TERMINAL_TYPE
import io.github.justlikecheese.xiaoyuan.portal.LoginException
import io.github.justlikecheese.xiaoyuan.portal.Portal
import io.github.justlikecheese.xiaoyuan.portal.PortalException
import io.github.justlikecheese.xiaoyuan.portal.PortalLog
import io.github.justlikecheese.xiaoyuan.portal.PortalState
import io.github.justlikecheese.xiaoyuan.portal.Status
import io.github.justlikecheese.xiaoyuan.portal.TERMINAL_TYPES
import io.github.justlikecheese.xiaoyuan.portal.Transport
import io.github.justlikecheese.xiaoyuan.portal.carrierName
import io.github.justlikecheese.xiaoyuan.portal.formatFee
import io.github.justlikecheese.xiaoyuan.portal.formatFlow
import io.github.justlikecheese.xiaoyuan.portal.formatLimit
import io.github.justlikecheese.xiaoyuan.portal.formatMac
import io.github.justlikecheese.xiaoyuan.portal.formatMinutes
import io.github.justlikecheese.xiaoyuan.portal.formatSeconds
import io.github.justlikecheese.xiaoyuan.portal.monotonic
import io.github.justlikecheese.xiaoyuan.portal.num
import io.github.justlikecheese.xiaoyuan.portal.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import java.io.File

const val FOREGROUND_REFRESH_MS = 30_000L
const val RECONNECT_MIN_INTERVAL = 5                // 兜底检测间隔的下限（秒），和门户的请求频率限制一致
const val RECONNECT_MAX_INTERVAL = 24 * 3600
const val RECONNECT_MAX_FAILURES = 3                 // 连续失败这么多次就暂停，免得用错密码反复尝试
const val RECONNECT_COOLDOWN = 15                    // 失败后，被动看到离线时至少隔这么多秒（乘以失败次数）再试
const val NETWORK_EVENT_DELAY_MS = 2000L             // 网络状态变化后等一会儿再查，变化往往一连串地来
const val PUBLIC_IP_TTL = 600                        // 公网 IP 最多这么多秒查一次（网络变化时会重查）
const val MASK = "••••••••"                          // 固定长度，不暴露账号、密码的位数

// 敏感字段：右上角眼睛关着时打码
val SENSITIVE_KEYS = setOf("uid", "AC", "NID", "v46ip", "v4ip", "lip", "ss5", "ss3", "olip", "olmac", "ss4", "myv6ip", "v6ip",
    "ss1", "opip")   // 真门户还有 ss1（MAC）、opip（IP）
val SECRET_INFO_KEYS = setOf("account", "user_name", "username", "real_name", "name", "mobile", "phone", "id_card", "email")

// ---------------------------------------------------------------- 给界面的数据

data class StatusView(
    val state: String,          // querying / online / offline / error
    val title: String,
    val detail: String,
    val online: Boolean = false,
    val account: String = "",
    val ip: String = "",
    val duration: String = "",
    val flow: String = "",
)

data class FieldView(val key: String, val title: String, val value: String, val secret: Boolean)

data class AccountView(
    val account: String, val isp: String, val carrier: String, val note: String, val password: String,
    val passwordState: String,   // saved / none / lost
    val full: String, val terminalType: Int, val terminalName: String, val lastUsed: String,
)

data class SettingsView(
    val autoLogin: Boolean, val closeAfterAutoLogin: Boolean, val loginExpanded: Boolean, val portalHost: String,
    val terminalType: Int, val theme: String, val color: String, val autoRefresh: Boolean,
    val guardNotify: Boolean = false,   // 守护通知：通知栏显示保活守护的状态
)

data class ReconnectView(
    val enabled: Boolean, val interval: Int, val intervalText: String, val networkEvents: Boolean, val account: String,
    val state: String,   // off / reconnecting / paused / watching
    val lastCheck: String, val lastState: String, val count: Int, val failures: Int,
    val since: Long = 0,   // 开启自动重连的时间（毫秒），自动重连页的按钮上显示已经守了多久
)

/** 一条重连记录：time 为“月-日 时:分:秒”，kind 为 success / info / warning / error，source 为“应用”或“守护”。 */
data class HistoryItem(val time: String, val kind: String, val text: String, val detail: String = "", val source: String = "应用")

/** 保活方式和守护状态（mode 为 Guard.SERVICE 时没有守护）。 */
data class GuardView(
    val mode: String, val running: Boolean, val state: String, val pingMs: String, val at: Long, val pssKb: Int = 0,
    val started: Long = 0,   // 守护启动的时间（秒）
)

data class LogInfo(val files: Int, val sizeText: String, val since: String, val keepDays: Int)

/** 导入预览里的一个账号。 */
data class ImportItem(val account: String, val label: String, val hasPassword: Boolean, val exists: Boolean)

/** 首页“公网 IP”卡片：ip 为空时看 hint（登录后查询 / 查询中 / 失败原因）。 */
data class PublicIpView(val ip: String = "", val place: String = "", val isp: String = "", val hint: String = "", val loading: Boolean = false)

/** kind：success / info / warning / error */
data class Toast(val kind: String, val title: String, val content: String, val durationMs: Long)

data class TerminalOption(val name: String, val value: Int)

// ---------------------------------------------------------------- 状态详情

private fun rawInt(raw: JsonObject, key: String): Long? =
    raw[key]?.takeIf { it != JsonNull }?.text()?.trim()?.toDoubleOrNull()?.toLong()

private fun fmt(key: String, fn: (Long) -> String): (JsonObject) -> String = { raw -> rawInt(raw, key)?.let(fn) ?: "" }

private fun txt(key: String, fn: (String) -> String = { it }): (JsonObject) -> String = { raw ->
    raw[key].text().trim().let { if (it.isNotEmpty()) fn(it) else "" }
}

private class DetailField(val key: String, val title: String, val get: (JsonObject) -> String, val secret: Boolean)

// 含义靠数值和页面代码推断，不确定的标了“推测”
private val DETAIL_FIELDS = listOf(
    DetailField("NID", "实名", txt("NID"), true),
    DetailField("actt", "本次在线时长", fmt("actt", ::formatSeconds), false),
    DetailField("fee", "余额", fmt("fee", ::formatFee), false),
    DetailField("olno", "本账号在线设备", fmt("olno") { "$it 台" }, false),
    DetailField("olip", "同账号另一设备 IP（推测）", txt("olip"), true),
    DetailField("olmac", "同账号另一设备 MAC（推测）", txt("olmac", ::formatMac), true),
    DetailField("ollm", "在线设备上限（推测）", fmt("ollm") { "$it 台" }, false),
    DetailField("oltime", "时长上限", fmt("oltime") { formatLimit(it, ::formatMinutes) }, false),
    DetailField("olflow", "流量上限", fmt("olflow") { formatLimit(it, ::formatFlow) }, false),
    DetailField("ss4", "本机 MAC", txt("ss4") { formatMac(it).ifEmpty { "门户未获取" } }, true),
    DetailField("vid", "VLAN", txt("vid"), false),
    DetailField("ss6", "认证服务器", txt("ss6"), false),
    DetailField("aolno", "全校在线终端", fmt("aolno") { "$it 台" }, false),
    DetailField("gid", "用户组（推测）", txt("gid"), false),
    DetailField("ispid", "运营商编号", txt("ispid"), false),
)

// ---------------------------------------------------------------- 日志

// 日志里的敏感信息：请求参数里的账号、IP、MAC，返回里的账号、实名、IP、MAC，以及事件里的 acct=
private val MASK_PATTERNS = listOf(
    Regex("((?:user_account|wlan_user_ip|wlan_user_mac|DDDDD)=)[^&\\s]*") to "$1••••",
    Regex("(\"(?:" + (SENSITIVE_KEYS + SECRET_INFO_KEYS).sorted().joinToString("|") + ")\":\\s*\")[^\"]*(\")") to
        "$1••••••••$2",
    Regex("(acct=)\\S+") to "$1••••",
)

fun maskLog(text: String): String = MASK_PATTERNS.fold(text) { t, (p, r) -> p.replace(t, r) }

/** 写进请求日志的“事件”行，账号用 acct= 标出，方便导出时打码。 */
fun logEvent(message: String) = PortalLog.info("[事件] $message")

fun formatSize(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", n / 1024.0)
    else -> String.format(Locale.ROOT, "%.2f MB", n / 1024.0 / 1024.0)
}

fun terminalName(value: Int): String = TERMINAL_TYPES.firstOrNull { it.second == value }?.first ?: "默认"

fun formatInterval(seconds: Int): String = when {
    seconds % 3600 == 0 -> "${seconds / 3600} 小时"
    seconds % 60 == 0 -> "${seconds / 60} 分钟"
    else -> "$seconds 秒"
}

fun describe(err: Throwable): String =
    if (err is LoginException || err is PortalException || err is OpCancelled) err.message ?: ""
    else "${err.javaClass.simpleName}: ${err.message}"

fun guardModeName(mode: String) = when (mode) {
    Guard.ROOT -> "Root"
    Guard.ADB -> "ADB"
    else -> "前台服务"
}

/** 正在进行的登录 / 注销被用户的新操作取消了。 */
class OpCancelled : Exception("已取消")

private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
private val HISTORY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
private val LAST_USED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 界面背后的逻辑，逐个方法对照桌面端 backend.py 的 Backend 移植（登录、切换、自动登录、自动刷新、自动重连、
 * 日志导出、设置）。所有方法都在主线程调用，网络请求放到 IO 线程。
 */
class Backend(
    private val context: Context,
    val store: AccountStore,
    val settings: Settings,
    private val log: ArchivingLog,
    private val net: NetworkWatcher?,
    private val transport: Transport,
) : NetworkWatcher.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 调试用：改连本地假门户（adb reverse 后传 http://127.0.0.1:18080），优先于设置里的门户地址。 */
    var portalOverride: String? = null

    private val _status = MutableStateFlow(statusView("querying", "正在查询…", ""))
    val status: StateFlow<StatusView> = _status
    private val _details = MutableStateFlow(detailList(JsonObject(emptyMap())))
    val details: StateFlow<List<FieldView>> = _details
    private val _rawJson = MutableStateFlow(JsonObject(emptyMap()))
    val rawJson: StateFlow<JsonObject> = _rawJson          // chkstatus 的全部原始字段
    private val _accounts = MutableStateFlow<List<AccountView>>(emptyList())
    val accounts: StateFlow<List<AccountView>> = _accounts
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _revealed = MutableStateFlow(false)
    val revealed: StateFlow<Boolean> = _revealed
    private val _settings = MutableStateFlow(settingsView())
    val settingsState: StateFlow<SettingsView> = _settings
    // 初始值在 init 里算：用到的重连状态字段声明在后面
    private val _reconnect = MutableStateFlow(ReconnectView(false, 60, "1 分钟", false, "", "off", "", "", 0, 0))
    val reconnect: StateFlow<ReconnectView> = _reconnect
    private val _guard = MutableStateFlow(GuardView(Guard.SERVICE, false, "", "", 0))
    val guard: StateFlow<GuardView> = _guard
    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val reconnectHistory: StateFlow<List<HistoryItem>> = _history
    private val _logInfo = MutableStateFlow(LogInfo(0, "0 B", "", KEEP_DAYS))
    val logInfo: StateFlow<LogInfo> = _logInfo
    private val _notice = MutableStateFlow("")
    val noticeText: StateFlow<String> = _notice              // 门户公告，空串表示没有
    private val _noticePopup = MutableStateFlow(false)
    val noticePopup: StateFlow<Boolean> = _noticePopup       // 公告弹窗是否显示
    private val _throttle = MutableStateFlow(0)
    val throttle: StateFlow<Int> = _throttle   // 还要排队几秒，0 表示没有
    private val _sessionSince = MutableStateFlow<Long?>(null)
    val sessionSince: StateFlow<Long?> = _sessionSince       // 本机记录的本次登录开始时间（毫秒），不在线为 null
    private val _loginAccount = MutableStateFlow("")
    val loginAccount: StateFlow<String> = _loginAccount      // 点“登录”时用哪个已保存账号，空串表示没有可用的
    private val _publicIp = MutableStateFlow(PublicIpView(hint = "查询中…"))
    val publicIp: StateFlow<PublicIpView> = _publicIp
    private var publicAt = 0.0                               // 上次查公网 IP 的时间，过了 PUBLIC_IP_TTL 秒再查
    /** Wi-Fi 上本机的 IPv4（内网 IP），没连 Wi-Fi 为空串。 */
    val localIp: StateFlow<String> = net?.localIp ?: MutableStateFlow("")

    private val toastChannel = Channel<Toast>(Channel.BUFFERED)
    val toasts = toastChannel.receiveAsFlow()
    private val _edit = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val editRequested: SharedFlow<String> = _edit          // 让界面打开账号编辑对话框（"" 为新增）
    private val _page = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 8)   // 留一个：从通知冷启动时界面还没开始收
    val pageRequested: SharedFlow<Int> = _page             // 让界面切到某一页（0 登录、1 账号管理、2 自动重连、3 设置）
    private val _close = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeRequested: SharedFlow<Unit> = _close

    val carriers = CARRIERS
    val terminalTypes = TERMINAL_TYPES.map { TerminalOption(it.first, it.second) }

    /** 登录页、账号编辑用：第一项“默认”跟随设置里的全局值。 */
    val terminalOptions: List<TerminalOption>
        get() = listOf(TerminalOption("默认（${terminalName(terminalType())}）", -1)) + terminalTypes

    val lastAccount: String get() = store.last()?.account ?: ""
    val defaultHost = "http://$DEFAULT_HOST"

    private var refreshing = false
    private val refreshWaiters = mutableListOf<(Status?) -> Unit>()   // 查询进行中又有人要结果：等这一次的，不重复请求
    var lastStatus: Status? = null
        private set
    private var started = false

    // 门户公告。本校门户没开账号信息接口（page/loadUserInfo 回复“请确认选择的用户基本信息是否启用”），不查
    private var notice = ""
    private var noticePopped = ""   // 本次运行已经弹过的公告，同一条只自动弹一次

    // 门户请求频率限制：排队时界面显示倒计时
    private var throttleUntil = 0.0
    private var throttleJob: Job? = null

    /**
     * 正在进行的登录或注销，同一时间只有一个。sent：认证请求已经发给门户，撤不回来了。
     * restore：撤销时把界面退回点之前的样子；cancelled：通知发起者这次被取消了。
     */
    private class Op(val restore: () -> Unit, val expected: (Int) -> Status?, val cancelled: () -> Unit) {
        var job: Job? = null
        @Volatile var inFlight = false   // 认证请求发出去了、还没回复
        @Volatile var commits = 0        // 门户确认成功的认证请求数（切换账号是注销 + 登录两步）
        val sent get() = inFlight || commits > 0
    }
    private var op: Op? = null
    private val opOnThread = ThreadLocal<Op>()

    private var autoJob: Job? = null

    // 自动重连
    // 手动注销或连续失败后暂停；存到设置里，进程被杀、手机重启后也不会把用户刚注销的又登回去
    private var reconPaused: Boolean
        get() = settings.bool("Reconnect", "Paused")
        set(v) {
            if (settings.bool("Reconnect", "Paused") != v) settings.set("Reconnect", "Paused", v)
            syncGuardPaused()
        }
    private var reconBusy = false
    // 连续失败次数和冷却截止时间也存到设置里：守护每次拉起的都是新进程，只记在内存里就永远数不到“连续失败 3 次暂停”
    private var reconFailures: Int
        get() = settings.int("Reconnect", "Failures")
        set(v) {
            if (settings.int("Reconnect", "Failures") != v) settings.set("Reconnect", "Failures", v)
        }
    private var reconCount = 0
    private var reconLastCheck = ""
    private var reconLastState = ""          // online / offline / unreachable，用于只在状态变化时记一条
    private val reconHistory = ArrayDeque<HistoryItem>()
    // 失败后的冷却：被动看到离线时，这之前不再尝试（按 monotonic 秒用，存成墙上时间毫秒，换进程、重启手机都对）
    private var reconNotBefore: Double
        get() {
            val ms = settings.string("Reconnect", "NotBefore").toLongOrNull() ?: return 0.0
            return monotonic() + (ms - System.currentTimeMillis()) / 1000.0
        }
        set(v) {
            val ms = if (v <= 0.0) "" else (System.currentTimeMillis() + ((v - monotonic()) * 1000).toLong()).toString()
            if (settings.string("Reconnect", "NotBefore") != ms) settings.set("Reconnect", "NotBefore", ms)
        }
    private var reconForce = false           // 正在进行的查询是主动检测发起的（不受冷却限制）
    // 兜底检测：单次定时器，每拿到一次状态（无论谁查的）就重新计时，所以从不和别的查询叠在一起
    private var reconnectJob: Job? = null
    // 网络状态变化后稍等再查
    private var networkJob: Job? = null
    private var networkReason = ""

    init {
        PortalState.waitListener = { seconds -> scope.launch { setThrottle(seconds) } }
        PortalState.sendListener = { opOnThread.get()?.inFlight = true }
        PortalState.doneListener = { ok ->
            opOnThread.get()?.let {
                it.inFlight = false
                if (ok) it.commits++
            }
        }
        net?.listener = this
        emitAccounts()
        emitReconnect()
        applyReconnectTimer()
        if (settings.bool("Reconnect", "Enabled") && settings.string("Reconnect", "Since").isEmpty()) {
            settings.set("Reconnect", "Since", System.currentTimeMillis().toString())
        }
        // 守护状态启动时同步读一次（一个小文件）：常驻服务要不要开取决于守护在不在跑，免得通知栏闪一下
        if (guardMode() != Guard.SERVICE) {
            readGuardNow()
            val g = Guard.status(context, reconnectInterval())
            // 应用更新后守护还是旧程序（ColorOS 不让更新广播拉起应用，没法在更新时换）：打开应用时换成新的
            if (g.running && g.version < Guard.VERSION && guardMode() == Guard.ROOT && settings.bool("Reconnect", "Enabled")) {
                logEvent("保活守护是旧版本（${g.version}），换成新的")
                restartGuard()
            }
        }
    }

    // ---- 内部 ----

    /** 实际使用的终端类型：账号或登录页选了具体类型就用它，选“默认”（-1）时用设置里的全局值。 */
    fun terminalType(chosen: Int = -1): Int {
        val valid = TERMINAL_TYPES.map { it.second }
        if (chosen in valid) return chosen
        val global = settings.int("Portal", "TerminalType")
        return if (global in valid) global else DEFAULT_TERMINAL_TYPE
    }

    fun makePortal(terminalType: Int = -1): Portal =
        Portal(portalOverride ?: settings.string("Portal", "Host"), terminalType = terminalType(terminalType),
            transport = transport)

    private fun <T> runAsync(fn: () -> T, onDone: (T?, Throwable?) -> Unit) {
        scope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) { fn() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            onDone(result.getOrNull(), result.exceptionOrNull())
        }
    }

    /** 在 IO 线程执行登录 / 注销，完成后在主线程回调 onDone；被 cancelOp 取消的不再回调。 */
    private fun <T> runOp(o: Op, fn: () -> T, onDone: (T?, Throwable?) -> Unit) {
        op = o
        setBusy(true)
        o.job = scope.launch {
            val result = try {
                Result.success(runInterruptible(Dispatchers.IO) {
                    opOnThread.set(o)
                    try { fn() } finally { opOnThread.remove() }
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            if (op !== o) return@launch
            op = null
            setBusy(false)
            setThrottle(0.0)
            onDone(result.getOrNull(), result.exceptionOrNull())
        }
    }

    /**
     * 用户又点了登录、注销或切换：取消正在进行的那个，不让用户干等。
     * 还在排队、没发给门户的直接撤销，界面退回点之前的样子，返回 true；已经发出去的不再等结果
     * （门户那边成没成以之后的查询为准），返回 false。没有进行中的请求也返回 false。
     */
    private fun cancelOp(): Boolean {
        val o = op ?: return false
        op = null
        o.job?.cancel()   // 正在排队等待的线程会被中断，请求不会发出
        setBusy(false)
        setThrottle(0.0)
        o.cancelled()
        if (!o.sent) {
            o.restore()
            return true
        }
        // 已经发出去了：按门户确认到哪一步算；还在等回复的不知道成没成
        lastStatus = if (o.inFlight) null else o.expected(o.commits)
        return false
    }

    private fun later(ms: Long, fn: () -> Unit) {
        scope.launch {
            delay(ms)
            fn()
        }
    }

    private fun toast(kind: String, title: String, content: String = "", ms: Long = 3000) {
        toastChannel.trySend(Toast(kind, title, content, ms))
    }

    /** 界面自己要弹的提示（同桌面端 showToast）。 */
    fun showToast(kind: String, title: String, content: String = "", ms: Long = 3000) = toast(kind, title, content, ms)

    private fun setBusy(on: Boolean) {
        _busy.value = on
    }

    private fun statusView(state: String, title: String, detail: String, st: Status? = null): StatusView {
        val online = st?.online == true
        return StatusView(
            state, title, detail, online,
            account = if (online) st.account else "",
            ip = st?.ip ?: "",
            duration = if (online) formatMinutes(st.minutes) else "",
            flow = if (online) formatFlow(st.flowKb) else "",
        )
    }

    private fun detailList(raw: JsonObject) = DETAIL_FIELDS.map { FieldView(it.key, it.title, it.get(raw), it.secret) }

    private fun accountView(a: Account) = AccountView(
        account = a.account, isp = a.isp, carrier = a.carrier, note = a.note, password = a.password,
        passwordState = if (a.passwordLost) "lost" else if (a.password.isNotEmpty()) "saved" else "none",
        full = a.full, terminalType = a.terminalType,
        terminalName = if (a.terminalType >= 0) terminalName(a.terminalType) else "默认",
        lastUsed = if (a.lastUsed > 0) Instant.ofEpochMilli((a.lastUsed * 1000).toLong())
            .atZone(ZoneId.systemDefault()).format(LAST_USED) else "-",
    )

    private fun emitAccounts() {
        _accounts.value = store.accounts.map(::accountView)
        _loginAccount.value = defaultAccount()?.account ?: ""
    }

    /** 点“登录”时用的账号：最近使用且保存了密码的，没有就随便一个保存了密码的。 */
    private fun defaultAccount(): Account? =
        store.last()?.takeIf { it.password.isNotEmpty() } ?: store.accounts.firstOrNull { it.password.isNotEmpty() }

    private fun settingsView() = SettingsView(
        autoLogin = settings.bool("Login", "AutoLogin"),
        closeAfterAutoLogin = settings.bool("Login", "CloseAfterAutoLogin"),
        loginExpanded = settings.bool("Login", "FormExpanded"),
        portalHost = settings.string("Portal", "Host"),
        terminalType = settings.int("Portal", "TerminalType"),
        theme = settings.string("Appearance", "Theme"),
        color = settings.string("Appearance", "Color"),
        autoRefresh = settings.bool("Status", "AutoRefresh"),
        guardNotify = settings.bool("Guard", "Notify"),
    )

    private fun emitSettings() {
        _settings.value = settingsView()
    }

    private fun reconnectView(): ReconnectView {
        val enabled = settings.bool("Reconnect", "Enabled")
        val state = when {
            !enabled -> "off"
            reconBusy -> "reconnecting"
            reconPaused -> "paused"
            else -> "watching"
        }
        return ReconnectView(
            enabled, reconnectInterval(), formatInterval(reconnectInterval()), net != null,
            settings.string("Reconnect", "Account"), state, reconLastCheck, reconLastState, reconCount, reconFailures,
            since = if (enabled) settings.string("Reconnect", "Since").toLongOrNull() ?: 0 else 0,
        )
    }

    private fun emitReconnect() {
        _reconnect.value = reconnectView()
        _history.value = reconHistory.reversed()   // 新的在前
    }

    /** 应用最近一次知道在线状态（查到、或登录注销时先按结果显示）的时间（毫秒）：守护通知据此判断用谁的状态。 */
    var statusAt = 0L
        private set

    private fun showStatus(st: Status?, err: Throwable?) {
        if (st != null) statusAt = System.currentTimeMillis()
        if (err != null) {
            _status.value = statusView("error", "无法连接门户", describe(err))
            _details.value = detailList(JsonObject(emptyMap()))
            _rawJson.value = JsonObject(emptyMap())
        } else if (st!!.online) {
            val suffix = if ("@" in st.account) "@" + st.account.substringAfter("@") else ""
            _status.value = statusView("online", "已在线", "${carrierName(suffix)} · 认证通过，可以正常上网", st)
        } else {
            _status.value = statusView("offline", "未登录", "点右下角的按钮登录", st)
        }
        if (st != null) {
            _details.value = detailList(st.raw)
            _rawJson.value = st.raw
        }
    }

    // ---- 本次登录时长：本机自己记，登录成功（或第一次看到在线）时开始，注销、掉线清零 ----

    /** since：开始时间（毫秒），默认现在；看到别处登录的在线会话时用门户的本次在线时长往前推。 */
    private fun startSession(account: String, since: Long = System.currentTimeMillis(), markTraffic: Boolean = true) {
        val now = since
        settings.set("Session", "Account", account.lowercase())
        settings.set("Session", "Since", now.toString())
        if (markTraffic) markTrafficStart()
        _sessionSince.value = now
    }

    private val TRAFFIC_KEYS = listOf("Rx0", "Tx0", "Boot")

    /** 记下本次连接开始时 Wi-Fi 的流量计数，首页“流量统计”显示从这里算起的上传、下载。 */
    private fun markTrafficStart() {
        val (rx, tx) = DeviceStats.wifiBytes()
        settings.set("Session", "Rx0", rx.toString())
        settings.set("Session", "Tx0", tx.toString())
        settings.set("Session", "Boot", DeviceStats.bootTime().toString())
    }

    /** 本次连接以来本机 Wi-Fi 的（上传，下载）字节数；不在线时为 0。中间重启过手机就从开机算起。 */
    fun sessionTraffic(): Pair<Long, Long> {
        if (_sessionSince.value == null) return 0L to 0L
        val (rx, tx) = DeviceStats.wifiBytes()
        val boot = settings.string("Session", "Boot").toLongOrNull()
        val sameBoot = boot != null && kotlin.math.abs(boot - DeviceStats.bootTime()) < 60_000
        val rx0 = if (sameBoot) settings.string("Session", "Rx0").toLongOrNull() ?: 0L else 0L
        val tx0 = if (sameBoot) settings.string("Session", "Tx0").toLongOrNull() ?: 0L else 0L
        return (tx - tx0).coerceAtLeast(0) to (rx - rx0).coerceAtLeast(0)
    }

    private fun trackSession(st: Status?) {
        st ?: return   // 连不上门户时不确定，保持原样
        if (!st.online) {
            if (settings.string("Session", "Since").isNotEmpty()) {
                settings.set("Session", "Account", "")
                settings.set("Session", "Since", "")
            }
            _sessionSince.value = null
            return
        }
        val since = settings.string("Session", "Since").toLongOrNull()
        if (since == null || settings.string("Session", "Account") != st.account.lowercase()) {
            // 不是本机刚登录的（比如之前用网页登录、或清过数据）：按门户的本次在线时长（actt，秒）算开始时间
            val actt = st.raw.num("actt").coerceAtLeast(0)
            startSession(st.account, System.currentTimeMillis() - actt * 1000)
        } else {
            if (settings.string("Session", "Boot").isEmpty()) markTrafficStart()   // 旧版本没记流量起点
            _sessionSince.value = since
        }
    }

    // ---- 状态 ----

    /** 打开界面时调一次（同一进程只生效一次）。 */
    fun startup() {
        if (started) return
        started = true
        refresh(false) { st ->
            loadNotice()
            val last = store.last()
            if (st != null && !st.online && settings.bool("Login", "AutoLogin") && last != null && last.password.isNotEmpty()) {
                login(last.account, last.password, last.isp, true, source = "auto", terminalType = last.terminalType)
            }
        }
    }

    /**
     * 查一次在线状态。quiet 用于自动刷新：不显示“正在查询”，正在登录时跳过。
     * 上一次查询还没回来时不再发请求，等那一次的结果（then 照样会被调用）。
     */
    fun refresh(quiet: Boolean = false, then: ((Status?) -> Unit)? = null) {
        if (then != null) refreshWaiters += then
        if (refreshing) return
        if (quiet && _busy.value && then == null) return
        refreshing = true
        if (!quiet) _status.value = _status.value.copy(state = "querying", title = "正在查询…")
        val portal = makePortal()
        runAsync({ portal.status() }) { st, err ->
            refreshing = false
            val result = if (err == null) st else null
            lastStatus = result
            showStatus(result, err)
            trackSession(result)
            updatePublicIp(result, err)
            val waiters = refreshWaiters.toList()
            refreshWaiters.clear()
            waiters.forEach { it(result) }
            observe(result)
        }
    }

    fun refreshWithToast() {
        loadNotice()
        refresh(false) { st ->
            if (st != null) toast("info", "状态已刷新", if (st.online) "当前在线" else "当前未登录", 2000)
        }
    }

    // ---- 公网 IP ----

    /** 每次拿到状态时调：校园网没登录时查不了（门户拦着），其余情况（在线、或不在校园网）过期了就查。 */
    private fun updatePublicIp(st: Status?, err: Throwable?) {
        if (err == null && st != null && !st.online) {
            _publicIp.value = PublicIpView(hint = "登录后查询")
            publicAt = 0.0
            return
        }
        if (monotonic() - publicAt > PUBLIC_IP_TTL) loadPublicIp()
    }

    /** 查公网 IP 的归属地和运营商（点首页的卡片也会调）。 */
    fun loadPublicIp() {
        if (_publicIp.value.loading) return
        publicAt = monotonic()
        _publicIp.value = _publicIp.value.copy(loading = true, hint = if (_publicIp.value.ip.isEmpty()) "查询中…" else "")
        runAsync({ IpLookup.queryWithFallback(transport) }) { r, err ->
            _publicIp.value = if (r != null) {
                PublicIpView(r.ip, r.place, r.isp)
            } else {
                publicAt = monotonic() - PUBLIC_IP_TTL + 60   // 失败了一分钟后再试
                PublicIpView(hint = "查询失败，点击重试")
            }
        }
    }

    // ---- 公告 ----

    /** 查门户公告；有新公告时自动弹窗（选过“不再显示”的那一条、或今天选过“今天不再显示”时不弹）。 */
    fun loadNotice() {
        val portal = makePortal()
        runAsync({ portal.notice() }) { text, err ->
            notice = if (err != null) "" else text ?: ""
            _notice.value = notice
            if (notice.isEmpty() || notice == noticePopped) return@runAsync
            if (settings.string("Notice", "Dismissed") == noticeKey(notice)) return@runAsync
            if (settings.string("Notice", "SnoozeDay") == LocalDate.now().toString()) return@runAsync
            noticePopped = notice
            _noticePopup.value = true
        }
    }

    private fun noticeKey(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /** 右上角的公告按钮。 */
    fun openNotice() {
        if (notice.isNotEmpty()) _noticePopup.value = true
    }

    fun closeNotice() {
        _noticePopup.value = false
    }

    /** 这一条公告以后不再自动弹出；门户换了新公告还会弹。 */
    fun neverShowNotice() {
        settings.set("Notice", "Dismissed", noticeKey(notice))
        closeNotice()
    }

    /** 今天之内不再自动弹出公告。 */
    fun snoozeNoticeToday() {
        settings.set("Notice", "SnoozeDay", LocalDate.now().toString())
        closeNotice()
    }

    /**
     * 界面在前台时每 FOREGROUND_REFRESH_MS 静默查一次（在线时长、已用流量跟着变），回到前台时马上查一次；
     * 切到后台就停，后台靠网络变化和自动重连的定时检测，不额外轮询门户。
     */
    fun setForeground(on: Boolean) {
        autoJob?.cancel()
        if (!on) return
        autoJob = scope.launch {
            while (isActive) {
                refresh(true)
                delay(FOREGROUND_REFRESH_MS)
            }
        }
    }

    // ---- 登录 / 注销 / 切换 ----

    /** 首页“登录”按钮：用最近使用且保存了密码的账号登录；还没有可用账号时打开账号编辑对话框。 */
    fun loginDefault() {
        val acc = defaultAccount()
        if (acc == null) {
            toast("info", "先添加一个账号", "账号保存在本机，密码加密保存", 4000)
            _edit.tryEmit(store.last()?.account ?: "")
            return
        }
        login(acc.account, acc.password, acc.isp, true, acc.terminalType)
    }

    /**
     * 账号对话框里的“测试登录”：用对话框里填的内容真的登录一次，但不保存账号（保存要点“保存”）。
     * 当前在线的是别的账号时会先注销。onResult(ok, 原因) 在完成或没能开始时回调。
     */
    fun testLogin(account: String, password: String, suffix: String, terminalType: Int, onResult: (Boolean, String) -> Unit) {
        val st = lastStatus
        when {
            account.isBlank() -> onResult(false, "请填写账号")
            password.isEmpty() -> onResult(false, "请填写密码")
            st != null && st.online && st.account.lowercase() == (account.trim() + suffix).lowercase() ->
                onResult(true, "这个账号已经在线")
            else -> login(account, password, suffix, false, terminalType, save = false) { ok, err ->
                onResult(ok, if (ok) "登录成功，配置可用" else describe(err!!))
            }
        }
    }

    /**
     * source：manual 手动 / auto 启动时自动登录 / reconnect 自动重连。onResult(ok, err) 在完成后回调。
     * terminalType 为登录页或账号里选的终端类型，-1 表示默认（跟随设置）；记住账号时一并保存。
     * save 为 false 时（测试登录）不改动已保存的账号。
     */
    fun login(
        account0: String, password: String, suffix: String, remember: Boolean, terminalType: Int = -1,
        source: String = "manual", save: Boolean = true, onResult: ((Boolean, Throwable?) -> Unit)? = null,
    ) {
        val account = account0.trim()
        if (account.isEmpty()) {
            toast("warning", "请填写账号")
            return
        }
        if (password.isEmpty()) {
            toast("warning", "请填写密码")
            return
        }
        val undone = cancelOp()   // 有登录 / 注销在进行：没发出去的撤销，发出去的不再等
        val st = lastStatus
        if (st != null && st.online && st.account.lowercase() == (account + suffix).lowercase()) {
            if (undone) logEvent("取消了还没发出的请求，仍然在线")
            else toast("info", "已经是这个账号在线")
            onResult?.invoke(true, null)
            return
        }
        // 已经用别的账号在线时，先注销再登录，就是“切换账号”
        val switching = st?.online == true
        val label = when (source) {
            "auto" -> "自动登录"
            "reconnect" -> "自动重连"
            else -> if (switching) "切换账号" else "登录"
        }
        // 界面先按登录成功显示（状态卡、计时、流量立刻切过去），失败或撤销时退回原样
        val before = _status.value to _sessionSince.value
        val trafficBefore = TRAFFIC_KEYS.map { settings.string("Session", it) }
        val startedAt = System.currentTimeMillis()
        markTrafficStart()
        _status.value = _status.value.copy(
            state = "online", title = "已在线", detail = "${carrierName(suffix)} · 正在登录…", online = true,
            account = account + suffix, duration = "", flow = "",
        )
        statusAt = System.currentTimeMillis()
        _sessionSince.value = startedAt
        val restore = {
            _status.value = before.first
            _sessionSince.value = before.second
            TRAFFIC_KEYS.zip(trafficBefore).forEach { (k, v) -> settings.set("Session", k, v) }
        }
        val portal = makePortal(terminalType)
        // 门户确认了几步之后的状态：切换账号确认一步是已注销，确认完是新账号在线
        val o = Op(restore, { n ->
            when {
                n >= (if (switching) 2 else 1) -> Status(true, account = account + suffix)
                switching && n == 1 -> Status(false)
                else -> null
            }
        }) {
            logEvent("${label}已取消 acct=$account$suffix")
            onResult?.invoke(false, OpCancelled())
        }
        runOp(o, {
            if (switching) portal.switch(account, password, suffix)
            else portal.login(account, password, suffix, 6.0)   // AC 说 IP 已在线一般是刚注销还没释放完，等几秒重试
        }) { _, err ->
            if (err != null) {
                logEvent("${label}失败 acct=$account$suffix：${describe(err)}")
                restore()
                val title = when {
                    source == "reconnect" -> "自动重连失败"
                    switching -> "切换失败"
                    err is LoginException -> "登录失败"
                    else -> "连接失败"
                }
                later(400) { refresh() }   // 失败后以门户实际状态为准
                toast("error", title, describe(err), 8000)
                onResult?.invoke(false, err)
                return@runOp
            }
            logEvent("${label}成功 acct=$account$suffix")
            startSession(account + suffix, startedAt, markTraffic = false)   // 流量起点点下去时已经记了
            val existing = store.get(account)
            var acc: Account? = null
            if (save && (remember || existing != null)) {
                val note = existing?.note ?: ""
                val savedPassword = if (remember) password else existing?.password ?: ""
                val savedType = if (remember) terminalType else existing?.terminalType ?: -1
                acc = store.upsert(Account(account, suffix, note, savedPassword, terminalType = savedType), touch = true)
                emitAccounts()
            }
            // 提示里不露出账号：有备注显示备注，否则只显示运营商
            val name = if (acc != null && acc.note.isNotEmpty()) "${acc.note} · ${acc.carrier}" else carrierName(suffix)
            val title = if (source == "reconnect") "已自动重连" else if (switching) "已切换账号" else "登录成功"
            toast("success", title, name)
            later(600) { refresh() }
            requestGuardCheck()
            if (source == "manual" && reconPaused) {
                reconPaused = false
                reconFailures = 0
                reconNotBefore = 0.0
                logEvent("手动登录，自动重连恢复")
                emitReconnect()
            }
            if (source == "auto" && settings.bool("Login", "CloseAfterAutoLogin")) later(1500) { _close.tryEmit(Unit) }
            onResult?.invoke(true, null)
        }
    }

    fun logout() {
        // 有登录在进行：没发出去的撤销（本来就不在线就到此为止），发出去的不再等，接着注销
        if (cancelOp() && lastStatus?.online == false) {
            logEvent("取消了还没发出的登录")
            return
        }
        // 界面先按已注销显示，失败或撤销时退回原样
        val before = _status.value to _sessionSince.value
        _status.value = _status.value.copy(
            state = "offline", title = "已注销", detail = "点右下角的按钮登录", online = false,
            account = "", duration = "", flow = "",
        )
        statusAt = System.currentTimeMillis()
        _sessionSince.value = null
        // 手动注销后不要马上又被自动重连登回去（点下去就暂停，注销完成前点“恢复”也不会被改回来）
        val pausedBefore = reconPaused
        val pause = settings.bool("Reconnect", "Enabled") && !reconPaused
        if (pause) {
            reconPaused = true
            emitReconnect()
        }
        val restore = {
            _status.value = before.first
            _sessionSince.value = before.second
            reconPaused = pausedBefore
            emitReconnect()
        }
        val portal = makePortal()
        runOp(Op(restore, { n -> if (n >= 1) Status(false) else null }) { logEvent("注销已取消") }, { portal.logout() }) { ok, err ->
            if (err != null) {
                logEvent("注销失败：${describe(err)}")
                restore()
                toast("error", "注销失败", describe(err), 8000)
                return@runOp
            }
            logEvent(if (ok == true) "注销成功" else "注销：门户回复本来就不在线")
            if (ok == true) toast("offline", "已注销") else toast("offline", "已注销", "本来就没有登录")
            if (ok == true) trackSession(Status(false))
            if (pause && reconPaused) logEvent("手动注销，自动重连暂停")
            emitReconnect()
            later(400) { refresh() }
            requestGuardCheck()
        }
    }

    // ---- 请求排队 ----

    private fun setThrottle(seconds: Double) {
        throttleUntil = if (seconds > 0) monotonic() + seconds else 0.0
        throttleJob?.cancel()
        if (seconds > 0) {
            throttleJob = scope.launch {
                while (isActive && monotonic() < throttleUntil) {
                    _throttle.value = maxOf(0, ceil(throttleUntil - monotonic()).toInt())
                    delay(250)
                }
                throttleUntil = 0.0
                _throttle.value = 0
            }
        } else {
            _throttle.value = 0
        }
    }

    // ---- 自动重连 ----
    //
    // 不靠高频轮询：
    // 1. 系统报告网络状态变化（Wi-Fi 断开、连上、被门户拦截、恢复上网）时，稍等 2 秒查一次；
    // 2. 任何一次查状态（启动、手动刷新、自动刷新、登录后）看到离线，都直接用上，不另外再查；
    // 3. 兜底定时器：距离上次拿到状态满“检测间隔”才查，每拿到一次状态就重新计时。

    fun reconnectInterval(): Int =
        settings.int("Reconnect", "Interval").coerceIn(RECONNECT_MIN_INTERVAL, RECONNECT_MAX_INTERVAL)

    /** 自动重连页的一条记录；kind 为 success/info/warning/error。 */
    private fun reconNote(kind: String, text: String, detail: String = "") {
        reconHistory.addLast(HistoryItem(LocalDateTime.now().format(HISTORY_TIME), kind, text, detail))
        while (reconHistory.size > 100) reconHistory.removeFirst()
        emitReconnect()
    }

    /** 重连用的账号：设置里指定的（要有保存密码），否则最近使用且有密码的那个。 */
    fun reconnectAccount(): Account? {
        store.get(settings.string("Reconnect", "Account"))?.takeIf { it.password.isNotEmpty() }?.let { return it }
        store.last()?.takeIf { it.password.isNotEmpty() }?.let { return it }
        return store.accounts.firstOrNull { it.password.isNotEmpty() }
    }

    private fun restartReconnectTimer() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectInterval() * 1000L)
            reconnectTick()
        }
    }

    private fun applyReconnectTimer() {
        if (settings.bool("Reconnect", "Enabled")) {
            restartReconnectTimer()   // 重新计时
        } else {
            reconnectJob?.cancel()
            networkJob?.cancel()
        }
    }

    override fun onReachability(state: String) {
        logEvent("系统报告网络状态：$state")
        PortalState.forgetTerminal()   // 可能换了网络、换了 IP
        publicAt = 0.0                 // 公网 IP 也可能变了，下次拿到状态时重新查
        if (state == "断开") {
            networkJob?.cancel()
            return
        }
        scheduleNetworkCheck("网络状态变化")
    }

    override fun onCaptivePortal(behind: Boolean) {
        if (behind) {
            logEvent("系统报告：需要登录门户才能上网")
            scheduleNetworkCheck("系统提示需要登录门户")
        }
    }

    private fun scheduleNetworkCheck(reason: String) {
        networkReason = reason
        networkJob?.cancel()   // 一连串变化只查一次
        networkJob = scope.launch {
            delay(NETWORK_EVENT_DELAY_MS)
            networkCheck()
        }
    }

    private fun networkCheck() {
        logEvent("${networkReason}，检测一次在线状态")
        if (settings.bool("Reconnect", "Enabled")) reconnectTick()
        else if (!_busy.value) refresh(true)   // 没开自动重连也更新一下首页的状态
    }

    /** 主动检测一次（兜底定时器、网络变化、开启/恢复时）。已有查询在进行时等它的结果，不重复请求。 */
    fun reconnectTick() {
        if (!settings.bool("Reconnect", "Enabled")) return
        if (_busy.value || reconBusy) {
            restartReconnectTimer()
            return
        }
        reconForce = true
        refresh(true)
    }

    /** 每拿到一次状态都会调到这里：开着自动重连、看到离线就重新登录。 */
    private fun observe(st: Status?) {
        val active = reconForce
        reconForce = false
        if (!settings.bool("Reconnect", "Enabled")) return
        restartReconnectTimer()   // 兜底定时器重新计时
        reconLastCheck = LocalDateTime.now().format(TIME)
        val state = if (st == null) "unreachable" else if (st.online) "online" else "offline"
        val changed = state != reconLastState
        reconLastState = state
        // 重连记录只记有问题的和问题解决的结果；平时测活一直在线、手动注销后的不在线都不记
        when {
            state == "unreachable" -> if (changed) {
                reconNote("warning", "连不上门户", "等网络恢复后再检测")
                reconIssue = true
            }
            state == "online" -> if (reconIssue) {
                reconNote("success", "恢复在线")
                reconIssue = false
            }
            !reconPaused && !_busy.value && !reconBusy -> {
                if (!active && monotonic() < reconNotBefore) {
                    // 刚失败过，等冷却结束或下一次主动检测
                } else {
                    val acc = reconnectAccount()
                    if (acc == null) {
                        if (changed) reconNote("warning", "检测到离线", "没有保存了密码的账号可用")
                        reconIssue = true
                    } else {
                        reconBusy = true
                        // 记录会一直留着，不随右上角眼睛变化，所以这里不写账号号码
                        reconIssue = true
                        reconNote("warning", "检测到离线", "正在用「${if (acc.note.isNotEmpty()) "${acc.note} · ${acc.carrier}" else acc.carrier}」重新登录")
                        logEvent("自动重连：检测到离线 acct=${acc.full}")
                        login(acc.account, acc.password, acc.isp, true, acc.terminalType, "reconnect", onResult = ::reconnectDone)
                        if (!_busy.value) reconBusy = false   // 没能开始登录
                    }
                }
            }
        }
        emitReconnect()
    }

    /** 记过问题（掉线、连不上）还没恢复：恢复在线时补一条“恢复在线”。 */
    private var reconIssue = false

    private fun reconnectDone(ok: Boolean, err: Throwable?) {
        reconBusy = false
        restartReconnectTimer()
        if (err is OpCancelled) {
            logEvent("自动重连被手动操作打断")
            emitReconnect()
            return
        }
        if (ok) {
            reconCount += 1
            reconFailures = 0
            reconNotBefore = 0.0
            reconLastState = "online"
            reconIssue = false
            reconNote("success", "自动重连成功")
            return
        }
        reconFailures += 1
        reconNotBefore = monotonic() + RECONNECT_COOLDOWN * reconFailures
        reconNote("error", "自动重连失败", describe(err!!))
        if (reconFailures >= RECONNECT_MAX_FAILURES) {
            reconPaused = true
            reconNote("error", "自动重连已暂停", "连续失败 $RECONNECT_MAX_FAILURES 次，检查账号密码后点“恢复”")
            logEvent("自动重连连续失败 $RECONNECT_MAX_FAILURES 次，已暂停")
            toast("warning", "自动重连已暂停", "连续失败 $RECONNECT_MAX_FAILURES 次，请检查账号密码", 6000)
        }
    }

    fun setReconnectEnabled(on: Boolean) {
        if (settings.bool("Reconnect", "Enabled") == on) return
        settings.set("Reconnect", "Enabled", on)
        settings.set("Reconnect", "Since", if (on) System.currentTimeMillis().toString() else "")
        // 用守护保活时：开自动重连就拉起守护，关掉就让它退出
        val mode = guardMode()
        if (mode != Guard.SERVICE) {
            if (on) restartGuard() else runAsync({ Guard.stop(context) }) { _, _ -> refreshGuard() }
        }
        reconPaused = false
        reconFailures = 0
        reconNotBefore = 0.0
        reconLastState = ""
        applyReconnectTimer()
        logEvent(if (on) "开启自动重连" else "关闭自动重连")
        emitSettings()
        emitReconnect()
        if (on) reconnectTick()   // 开启后立刻检测一次
    }

    /** 兜底检测间隔，任意秒数，限制在 5 秒到 24 小时之间。 */
    fun setReconnectInterval(seconds: Int) {
        val v = seconds.coerceIn(RECONNECT_MIN_INTERVAL, RECONNECT_MAX_INTERVAL)
        if (settings.int("Reconnect", "Interval") != v) {
            settings.set("Reconnect", "Interval", v)
            applyReconnectTimer()
            writeGuardConfig()
        }
        emitReconnect()
    }

    fun setReconnectAccount(account: String) {
        settings.set("Reconnect", "Account", account)
        emitReconnect()
    }

    fun resumeReconnect() {
        reconPaused = false
        reconFailures = 0
        reconNotBefore = 0.0
        reconLastState = ""
        logEvent("手动恢复自动重连")
        emitReconnect()
        reconnectTick()
    }

    fun clearReconnectHistory() {
        reconHistory.clear()
        emitReconnect()
        if (guardMode() != Guard.SERVICE) runAsync({ Guard.clearEvents(context) }) { _, _ -> refreshGuardEvents() }
    }

    private val _guardEvents = MutableStateFlow<List<HistoryItem>>(emptyList())
    val guardEvents: StateFlow<List<HistoryItem>> = _guardEvents   // 守护的记录（守护方式时并进“重连记录”）

    /** 重新读一次守护的事件记录（读 2 KB 的小文件）。 */
    fun refreshGuardEvents() {
        if (guardMode() == Guard.SERVICE) {
            _guardEvents.value = emptyList()
            return
        }
        runAsync({ Guard.events(context).map { HistoryItem(it.time, it.kind, it.text, it.detail, "守护") } }) { list, _ ->
            if (list != null) _guardEvents.value = list
        }
    }

    /** 把日志（含已压缩的旧日志）导出到 cache/share/ 下，回调分享用的文件；眼睛关着时账号、IP、MAC 打码。 */
    fun prepareLogShare(onReady: (File?) -> Unit) {
        val mask = !_revealed.value
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        runAsync({
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }   // 只留这一份
            val f = File(dir, "校园网登录日志-$stamp.log")
            f.outputStream().use { export(log.dir, log.path, it, if (mask) ::maskLog else null) }
            f
        }) { f, err ->
            if (err != null) toast("error", "导出日志失败", err.message ?: err.toString(), 6000)
            onReady(f)
        }
    }

    // ---- 日志 ----

    fun refreshLogInfo() {
        val s = summary(log.dir, log.path)
        _logInfo.value = LogInfo(s.files, formatSize(s.size), s.since, KEEP_DAYS)
    }

    /** 把全部日志（含已压缩的旧日志）按时间顺序合并写进 out。右上角眼睛关着时账号、IP、MAC 打码。 */
    fun exportLog(open: () -> OutputStream?, name: String) {
        val mask = !_revealed.value
        runAsync({
            (open() ?: throw java.io.IOException("打不开要保存的文件")).use {
                export(log.dir, log.path, it, if (mask) ::maskLog else null)
            }
        }) { _, err ->
            if (err != null) toast("error", "导出失败", err.message ?: err.toString(), 6000)
            else toast("success", "日志已导出", name, 4000)
        }
    }

    /** 一键切换：用这个账号登录，当前在线的是别的账号时会先自动注销。没保存密码时打开编辑对话框。 */
    fun switchTo(account: String) {
        val acc = store.get(account) ?: return
        if (acc.password.isEmpty()) {
            toast("warning", "这个账号没有保存密码", "填好密码保存后再切换", 4000)
            _edit.tryEmit(acc.account)
            return
        }
        _page.tryEmit(0)
        login(acc.account, acc.password, acc.isp, true, acc.terminalType)
    }

    // ---- 账号 ----

    fun saveAccount(oldAccount: String, account0: String, isp: String, note: String, password: String, terminalType: Int): Boolean {
        val account = account0.trim()
        if (account.isEmpty()) return false
        val old = if (oldAccount.isNotEmpty()) store.get(oldAccount) else null
        var acc = Account(account, isp, note.trim(), password, terminalType = terminalType)
        if (old != null) {
            acc.lastUsed = old.lastUsed
            acc = store.rename(old.account, acc)
            toast("success", "已保存", accountTitle(acc))
        } else {
            val replaced = store.get(account) != null
            acc = store.upsert(acc)
            toast("success", if (replaced) "已更新" else "已保存", accountTitle(acc))
        }
        emitAccounts()
        return true
    }

    fun deleteAccount(account: String) {
        val acc = store.get(account) ?: return
        val name = accountTitle(acc)
        store.remove(account)
        emitAccounts()
        toast("success", "已删除", name)
    }

    // ---- 账号迁移（和电脑互相导入导出，格式见 Transfer.kt / transfer.py） ----

    private var pendingImport: List<Account> = emptyList()

    /** 把选中的账号（含保存的密码）加密成迁移数据；失败时弹提示、返回 null。 */
    fun exportAccounts(accounts: Collection<String>, code: String): String? {
        val chosen = store.accounts.filter { it.account in accounts }
        if (chosen.isEmpty()) {
            toast("warning", "请至少选一个账号")
            return null
        }
        return try {
            Transfer.encode(chosen, code.trim())
        } catch (e: Transfer.TransferException) {
            toast("warning", "无法导出", e.message ?: "", 4000)
            null
        }
    }

    /** 用导入码解开迁移数据（PBKDF2 要算一会儿，在后台线程调），返回给界面勾选；解不开时返回原因。 */
    fun previewImport(text: String, code: String): Result<List<ImportItem>> = try {
        val list = Transfer.decode(text, code.trim())
        pendingImport = list
        Result.success(list.map { ImportItem(it.account, accountTitle(it), it.password.isNotEmpty(), store.get(it.account) != null) })
    } catch (e: Transfer.TransferException) {
        Result.failure(e)
    }

    /** 导入勾选的账号：已有的更新运营商、备注、终端类型和密码（导入的没带密码、备注时保留原来的）。 */
    fun applyImport(accounts: Collection<String>): Int {
        var n = 0
        for (a in pendingImport) {
            if (a.account !in accounts) continue
            store.get(a.account)?.let { old ->
                a.lastUsed = old.lastUsed
                if (a.password.isEmpty()) a.password = old.password
                if (a.note.isEmpty()) a.note = old.note
            }
            store.upsert(a)
            n++
        }
        pendingImport = emptyList()
        if (n > 0) {
            emitAccounts()
            toast("success", "已导入 $n 个账号")
        }
        return n
    }

    /** 通知栏、磁贴里怎么称呼当前在线的账号：已保存的用备注或运营商，不显示账号号码。 */
    fun onlineLabel(): String {
        val uid = _status.value.account.lowercase()
        val acc = store.accounts.firstOrNull { it.full.lowercase() == uid }
        return when {
            acc == null -> carrierName(if ("@" in uid) "@" + uid.substringAfter("@") else "")
            acc.note.isNotEmpty() -> "${acc.note} · ${acc.carrier}"
            else -> acc.carrier
        }
    }

    /**
     * 通知上称呼当前登录的账号：只用备注（没写备注、不知道是哪个账号时为空串，通知上就只写“已在线”）。
     * 应用没查过状态（守护叫起来的）时用记着的本次登录账号。
     */
    fun onlineName(): String {
        val uid = _status.value.account.ifEmpty { settings.string("Session", "Account") }.lowercase()
        if (uid.isEmpty()) return ""
        val acc = store.accounts.firstOrNull { it.full.lowercase() == uid }
        return acc?.note ?: ""
    }

    /** 本次登录开始的时间（毫秒）：应用没查过状态（守护叫起来的）时用记着的；不知道为 null。 */
    fun sessionStart(): Long? = _sessionSince.value ?: settings.string("Session", "Since").toLongOrNull()

    /** 提示里怎么称呼一个账号：有备注用备注；没备注时眼睛开着显示账号，关着只显示运营商。 */
    fun accountTitle(acc: Account): String =
        when {
            acc.note.isNotEmpty() -> "${acc.note} · ${acc.carrier}"
            _revealed.value -> "${acc.account} · ${acc.carrier}"
            else -> acc.carrier
        }

    // ---- 其他 ----

    fun setRevealed(on: Boolean) {
        _revealed.value = on
    }

    fun copy(text: String, title: String) {
        if (text.isEmpty()) return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, text))
        // Android 13 起系统自己会弹出“已复制”，不再重复提示
        if (Build.VERSION.SDK_INT < 33) toast("success", "已复制", title, 1500)
    }

    fun setSetting(key: String, value: Any) {
        val (group, name) = when (key) {
            "autoLogin" -> "Login" to "AutoLogin"
            "closeAfterAutoLogin" -> "Login" to "CloseAfterAutoLogin"
            "loginExpanded" -> "Login" to "FormExpanded"
            "portalHost" -> "Portal" to "Host"
            "terminalType" -> "Portal" to "TerminalType"
            "theme" -> "Appearance" to "Theme"
            "color" -> "Appearance" to "Color"
            "guardNotify" -> "Guard" to "Notify"
            else -> return
        }
        var v = value
        if (key == "portalHost") v = value.toString().trim().ifEmpty { defaultHost }
        if (key == "terminalType" && TERMINAL_TYPES.none { it.second == value }) return
        val current: Any = when (v) {
            is Boolean -> settings.bool(group, name)
            is Int -> settings.int(group, name)
            else -> settings.string(group, name)
        }
        if (current == v) return
        settings.set(group, name, v)
        if (key == "portalHost") writeGuardConfig()
        if (key == "guardNotify" && guardMode() != Guard.SERVICE) {
            val on = v == true
            runAsync({ Guard.setNotify(context, on) }) { _, _ -> }
        }
        emitSettings()
    }

    // ---- 保活守护（Root / ADB），见 Guard ----

    fun guardMode(): String = settings.string("Guard", "Mode").takeIf { it in Guard.MODES } ?: Guard.SERVICE

    /** 重新读一次守护的状态（读一个 2 KB 的小文件）。 */
    fun refreshGuard() {
        val mode = guardMode()
        if (mode == Guard.SERVICE) {
            _guard.value = GuardView(mode, false, "", "", 0)
            return
        }
        runAsync({ Guard.status(context, reconnectInterval()) }) { g, _ ->
            if (g != null && guardMode() == mode) _guard.value = GuardView(mode, g.running, g.state, g.pingMs, g.at, g.pssKb, g.started)
        }
    }

    /** 同步读一次守护状态（一个 2 KB 的小文件，主线程也可以）：守护拉起应用更新通知时要马上用。 */
    fun readGuardNow() {
        val mode = guardMode()
        if (mode == Guard.SERVICE) return
        val g = Guard.status(context, reconnectInterval())
        _guard.value = GuardView(mode, g.running, g.state, g.pingMs, g.at, g.pssKb, g.started)
    }

    fun guardNotify(): Boolean = settings.bool("Guard", "Notify")

    /** 请求守护马上检测一次：登录、注销后守护的状态（和通知）不用等到下一轮。 */
    fun requestGuardCheck() {
        if (guardMode() != Guard.SERVICE) runAsync({ Guard.requestCheck(context) }) { _, _ -> }
    }

    /** 切到某一页（通知点开时到自动重连页）。 */
    fun showPage(page: Int) {
        _page.tryEmit(page)
    }

    /** 界面切过去了：清掉留着的那个，免得界面重建时又切一次。 */
    fun pageShown() = _page.resetReplayCache()

    private fun guardPaused() = reconPaused || !settings.bool("Reconnect", "Enabled")

    private fun syncGuardPaused() {
        if (guardMode() != Guard.SERVICE) {
            val paused = guardPaused()
            runAsync({ Guard.setPaused(context, paused) }) { _, _ -> }
        }
    }

    /** 检测间隔、门户地址变了：改守护的控制区（门户是域名时要联网解析，放后台）。 */
    private fun writeGuardConfig() {
        if (guardMode() == Guard.SERVICE) return
        val interval = reconnectInterval()
        val host = settings.string("Portal", "Host")
        runAsync({ Guard.writeConfig(context, interval, Guard.portalAddress(host)) }) { _, _ -> }
    }

    /**
     * 换保活方式。Root：申请 su 后启动守护（第一次 Magisk 会弹授权框）；ADB：写好控制区和 start.sh，
     * 由界面给出命令让用户在电脑上执行。切回前台服务时停掉守护。onResult(成功, 说明)。
     */
    fun setGuardMode(mode: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (mode !in Guard.MODES) return
        val old = guardMode()
        if (mode == Guard.SERVICE) {
            settings.set("Guard", "Mode", mode)
            logEvent("保活方式：前台服务")
            refreshGuard()
            if (old != Guard.SERVICE) runAsync({ Guard.stop(context) }) { _, _ -> }
            onResult(true, "")
            return
        }
        val interval = reconnectInterval()
        val host = settings.string("Portal", "Host")
        val paused = guardPaused()
        val notify = guardNotify()
        runAsync({
            Guard.prepare(context, interval, Guard.portalAddress(host), paused, notify)   // 新守护启动后旧的会自己退出
            mode == Guard.ADB || Guard.start(context, mode)
        }) { ok, err ->
            if (ok == true) {
                settings.set("Guard", "Mode", mode)
                logEvent("保活方式：${guardModeName(mode)}")
                onResult(true, "")
                later(2500) { refreshGuard() }   // 守护第一轮检测写出状态后再读
            } else {
                onResult(false, err?.message ?: "没有 root 权限：请在 Magisk 里允许本应用")
            }
            refreshGuard()
        }
    }

    private var lastGuardStart = 0L

    /** 守护没在跑时按当前方式重新拉起（Root 可以；ADB 只能用户自己执行命令）。 */
    fun restartGuard(onResult: (Boolean) -> Unit = {}) {
        if (guardMode() != Guard.ROOT) {
            onResult(false)
            return
        }
        // 开自动重连、看门狗任务、开机广播可能前后脚都来拉：15 秒内拉过一次就不再拉，免得起两个守护
        val now = System.currentTimeMillis()
        if (now - lastGuardStart < 15_000) {
            onResult(true)
            return
        }
        lastGuardStart = now
        val interval = reconnectInterval()
        val host = settings.string("Portal", "Host")
        val paused = guardPaused()
        val notify = guardNotify()
        runAsync({
            Guard.prepare(context, interval, Guard.portalAddress(host), paused, notify)
            Guard.start(context, Guard.ROOT)
        }) { ok, _ ->
            logEvent(if (ok == true) "重新启动保活守护（Root）" else "重新启动保活守护失败（Root）：没有得到 su")
            onResult(ok == true)
            later(2500) { refreshGuard() }
        }
    }

    fun guardAdbCommand(): String = Guard.adbCommand(context)

    /** 守护发现掉线、拉起应用时调：检测一次，掉线就按自动重连的规则登录；全部结束后回调 onDone。 */
    fun guardCheck(reason: String = "保活守护发现掉线", onDone: () -> Unit) {
        logEvent("${reason}，检测一次")
        refresh(true) {   // 按被动检测处理：刚失败过还在冷却时不重试，免得每分钟被叫起来撞一次
            scope.launch {
                delay(500)
                val deadline = System.currentTimeMillis() + 90_000
                while ((_busy.value || reconBusy) && System.currentTimeMillis() < deadline) delay(300)
                onDone()
            }
        }
    }
}
