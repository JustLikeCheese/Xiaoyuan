package io.github.justlikecheese.xiaoyuan.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.justlikecheese.xiaoyuan.App
import io.github.justlikecheese.xiaoyuan.MainActivity
import io.github.justlikecheese.xiaoyuan.R
import io.github.justlikecheese.xiaoyuan.backend
import io.github.justlikecheese.xiaoyuan.core.Backend
import io.github.justlikecheese.xiaoyuan.core.Guard

/**
 * 自动重连的通知（只有这一个），样子同 LSPosed 的状态通知：渠道重要性设为最低，通知栏里折叠成一行
 * “校园网登录 • 已在线 · 账号备注 • 2 小时”，展开后第二行是“点按通知以打开主页”，状态栏不显示图标。
 * 点一下打开应用的主页。
 * 前台服务方式由 KeepAliveService 挂着（前台服务必须有通知）；Root / ADB 守护方式在设置里打开后显示，
 * 是普通通知，应用进程结束后还留着，守护状态变了用 am 拉起 GuardNotifyService 更新，更新完进程自己结束。
 */
object ReconnectNotifier {
    const val CHANNEL = "status"                       // 平时用：重要性最低，折叠显示
    private const val CHANNEL_SERVICE = "keepalive"    // 前台服务用：系统不让前台服务的通知用最低重要性（会把渠道改成低），只好分开
    const val ID = 1
    private const val UPDATE_ID = 3   // GuardNotifyService 进前台时用的临时通知，马上移除
    private const val PAGE_HOME = 0

    /** 通知的内容；show 为 false 时不显示。since 是本次登录开始的时间（毫秒），在线时显示登录了多久，0 不显示。 */
    data class Content(val show: Boolean, val title: String = "", val since: Long = 0, val publicTitle: String = title)

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "自动重连状态", NotificationManager.IMPORTANCE_MIN).apply {
                description = "开着自动重连时显示在线状态，在通知栏里折叠成一行"
                setShowBadge(false)
            })
        }
        if (nm.getNotificationChannel(CHANNEL_SERVICE) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "后台自动重连（前台服务）", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保活方式为前台服务时常驻，显示在线状态"
                setShowBadge(false)
            })
        }
        if (nm.getNotificationChannel("guard") != null) nm.deleteNotificationChannel("guard")   // 旧版单独的守护通知
    }

    /** 开着自动重连时显示：前台服务在跑时必须显示，用守护时看设置里的开关。 */
    fun content(b: Backend): Content {
        val r = b.reconnect.value
        val g = b.guard.value
        if (!r.enabled || !(g.mode == Guard.SERVICE || KeepAliveService.running || b.settingsState.value.guardNotify)) return Content(false)
        // 守护在跑、而且比应用新（守护叫起来的进程还没查过）就用守护检测的状态，否则用应用自己的
        val st = b.status.value
        val appKnows = (st.state == "online" || st.state == "offline") && b.statusAt >= g.at * 1000
        val state = if (g.mode != Guard.SERVICE && g.running && !appKnows) g.state else when (st.state) {
            "online", "offline" -> st.state
            "error" -> "unreachable"
            else -> "checking"
        }
        val name = b.onlineName()
        val title = when {
            r.state == "reconnecting" -> "正在自动重连…"
            state == "online" -> if (name.isEmpty()) "已在线" else "已在线 · $name"
            state == "offline" -> if (r.state == "paused") "未登录 · 自动重连已暂停" else "未登录"
            state == "unreachable" -> "连不上校园网门户"
            state == "wifi-down" -> "Wi-Fi 断开"
            state == "wifi-off" -> "Wi-Fi 已关闭"
            state == "other" -> "连的不是校园网"
            else -> "正在查询…"
        }
        val since = if (state == "online") b.sessionStart() ?: 0 else 0
        return Content(true, title, since, if (state == "online") "已在线" else title)
    }

    /** 显示或更新；不该显示时移除（前台服务还在跑时由它自己移除）。 */
    fun post(context: Context, c: Content) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // 前台服务在跑时这个通知归它，要用前台服务的渠道
        if (c.show) nm.notify(ID, build(context, c, service = KeepAliveService.running)) else if (!KeepAliveService.running) nm.cancel(ID)
    }

    fun build(context: Context, c: Content, service: Boolean = false, public: Boolean = false): Notification {
        // 点开到主页（应用开着停在别的页时也切回主页）
        val open = PendingIntent.getActivity(
            context, ID, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAGE, PAGE_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, if (service) CHANNEL_SERVICE else CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle(if (public) c.publicTitle else c.title)
            .setContentText("点按通知以打开主页")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(c.since > 0)
            .setWhen(if (c.since > 0) c.since else System.currentTimeMillis())   // 在线时显示登录了多久（“2 小时”）
            .setCategory(Notification.CATEGORY_SERVICE)
        // 标题里有账号：锁屏设了隐藏敏感内容时换成不带账号的版本
        if (public) builder.setVisibility(Notification.VISIBILITY_PUBLIC)
        else builder.setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(build(context, c, service, public = true))
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    /**
     * GuardNotifyService 是用 startForegroundService 拉起的，必须马上进前台：借一个临时通知进前台再马上移除
     * （不到 10 秒的前台服务，系统一般不显示它的通知）。不用自动重连的通知本身：前台服务的通知不能放在最低重要性的渠道。
     */
    fun enterForeground(service: Service) {
        val n = Notification.Builder(service, CHANNEL_SERVICE).setSmallIcon(R.drawable.ic_stat_guard).setContentTitle("正在更新").build()
        if (Build.VERSION.SDK_INT >= 34) service.startForeground(UPDATE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else service.startForeground(UPDATE_ID, n)
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }
}

/**
 * 守护状态变了时起来（am start-foreground-service，只有 root / shell 能调）：读一次守护状态更新通知，
 * 进程是为这个起来的就结束，只留守护。
 */
class GuardNotifyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val b = backend
        ReconnectNotifier.ensureChannel(this)
        b.readGuardNow()
        ReconnectNotifier.enterForeground(this)
        ReconnectNotifier.post(this, ReconnectNotifier.content(b))
        (application as App).finishDetached { stopSelf(startId) }
        return START_NOT_STICKY
    }
}
