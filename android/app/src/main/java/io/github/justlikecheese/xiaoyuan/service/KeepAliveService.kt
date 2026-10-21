package io.github.justlikecheese.xiaoyuan.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import io.github.justlikecheese.xiaoyuan.App
import io.github.justlikecheese.xiaoyuan.backend
import io.github.justlikecheese.xiaoyuan.core.Guard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 开着自动重连时常驻的前台服务：让进程在后台也活着（掉线检测、连上 Wi-Fi 自动登录都在进程里跑）。
 * 通知就是自动重连的那个（ReconnectNotifier，内容变了由 App 更新）。关掉自动重连就停。
 */
class KeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ReconnectNotifier.ensureChannel(this)
        val b = backend
        val n = ReconnectNotifier.build(this, ReconnectNotifier.content(b), service = true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ReconnectNotifier.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ReconnectNotifier.ID, n)
        }
        scope.launch {
            b.reconnect.collect { if (!it.enabled) stopSelf() }
        }
        b.startup()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_GUARD_CHECK) {
            // 保活守护发现掉线叫起来的：检测、登录，做完没有界面就结束整个进程，只留守护
            backend.guardCheck {
                val app = application as App
                if (backend.guardMode() != Guard.SERVICE && backend.guard.value.running && app.visibleActivities == 0) {
                    release()
                    stopSelf()
                    app.exitProcess()
                }
            }
        }
        return START_STICKY   // 被系统杀掉后有机会就重启
    }

    /** 从最近任务里划掉应用时，有的系统（ColorOS 等）会顺带杀掉服务：1 秒后用闹钟再拉起来。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!backend.reconnect.value.enabled) return
        val pi = PendingIntent.getForegroundService(this, RESTART_REQUEST, Intent(this, KeepAliveService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        getSystemService(AlarmManager::class.java).set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pi)
    }

    override fun onDestroy() {
        if (running) release()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 不再当前台服务：通知还该显示（用守护、设置里开了通知）就脱离服务留着，交给应用和守护更新；
     * 不该显示（关了自动重连等）就跟服务一起移除。
     */
    private fun release() {
        running = false
        val c = ReconnectNotifier.content(backend)
        stopForeground(if (c.show) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
        ReconnectNotifier.post(this, c)
    }

    companion object {
        private const val RESTART_REQUEST = 7
        const val ACTION_GUARD_CHECK = "io.github.justlikecheese.xiaoyuan.GUARD_CHECK"   // 保活守护（root / shell）发来

        /** 服务在不在跑（在跑时进程要留着，通知归它管）。 */
        @Volatile var running = false
            private set

        /** 按自动重连的开关启动或停止服务。后台时系统可能不让启动（安卓 12 起），那就等下次打开应用。 */
        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (!enabled) {
                context.stopService(intent)
                return
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w("KeepAlive", "暂时不能启动前台服务：$e")
            }
        }
    }
}
