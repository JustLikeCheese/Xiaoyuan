package io.github.justlikecheese.xiaoyuan

import android.app.Application
import android.content.Context
import io.github.justlikecheese.xiaoyuan.core.AccountStore
import io.github.justlikecheese.xiaoyuan.core.ArchivingLog
import io.github.justlikecheese.xiaoyuan.core.Backend
import io.github.justlikecheese.xiaoyuan.core.KeystoreCipher
import io.github.justlikecheese.xiaoyuan.core.NetworkWatcher
import io.github.justlikecheese.xiaoyuan.core.Settings
import io.github.justlikecheese.xiaoyuan.core.WifiTransport
import io.github.justlikecheese.xiaoyuan.portal.PortalLog
import io.github.justlikecheese.xiaoyuan.service.KeepAliveService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import io.github.justlikecheese.xiaoyuan.service.WatchdogJob
import android.app.Activity
import android.os.Bundle
import io.github.justlikecheese.xiaoyuan.core.Guard
import kotlinx.coroutines.flow.combine
import android.os.Process
import io.github.justlikecheese.xiaoyuan.service.ReconnectNotifier
import kotlinx.coroutines.delay

const val APP_NAME = "校园网登录"
const val APP_AUTHOR = "JustLikeCheese"
const val APP_URL = "https://github.com/JustLikeCheese/Xiaoyuan"
const val APP_LICENSE = "GNU GPL v3"
val APP_TITLE = "$APP_NAME v${BuildConfig.VERSION_NAME} · $APP_AUTHOR"

/** 整个进程共用一个 Backend：界面关掉重开时状态、自动重连都还在（同桌面端窗口一直开着的情况）。 */
class App : Application() {
    lateinit var backend: Backend
        private set

    override fun onCreate() {
        super.onCreate()
        val log = ArchivingLog(File(filesDir, "logs/portal.log"))
        PortalLog.sink = log::write
        val net = NetworkWatcher(this)
        backend = Backend(
            context = this,
            store = AccountStore(File(filesDir, "accounts.json"), KeystoreCipher()),
            settings = Settings(File(filesDir, "settings.json")),
            log = log,
            net = net,
            transport = WifiTransport(net),
        )
        net.start()
        // 自动重连开着时用前台服务常驻（掉线检测、连上 Wi-Fi 自动登录在后台也能跑），关掉就停
        ReconnectNotifier.ensureChannel(this)
        if (backend.guardMode() != Guard.SERVICE) Guard.setAppPid(this, Process.myPid())
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { visibleActivities++ }
            override fun onActivityStopped(activity: Activity) { visibleActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is MainActivity) mainCreated++
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // 常驻前台服务：开着自动重连、又没有守护在跑时才开（保活方式选了前台服务，或守护挂了时兜底）。
        // 用守护保活时应用进程可以完全退出，只在界面打开、或守护叫它登录时才起来。
        MainScope().launch {
            var started = false
            combine(backend.reconnect, backend.guard) { r, g -> r.enabled && (g.mode == Guard.SERVICE || !g.running) }
                .distinctUntilChanged()
                .collect { need ->
                    if (need) KeepAliveService.sync(this@App, true)
                    else if (started) KeepAliveService.sync(this@App, false)   // 别停掉守护叫起来登录的那次服务
                    started = need
                }
        }
        // 自动重连的通知：内容变了才更新（守护拉起应用、或界面开着时状态变了）
        MainScope().launch {
            combine(backend.settingsState, backend.reconnect, backend.guard, backend.status, backend.accounts) { _, _, _, _, _ ->
                ReconnectNotifier.content(backend)
            }
                .distinctUntilChanged()
                .collect { ReconnectNotifier.post(this@App, it) }
        }
        MainScope().launch {
            backend.reconnect.map { it.enabled }.distinctUntilChanged().collect {
                WatchdogJob.sync(this@App, it)   // 兜底：每 15 分钟看一眼服务、守护还在不在
            }
        }
    }

    /** 当前可见的界面数；为 0 时守护叫起来的登录做完就结束进程。 */
    @Volatile var visibleActivities = 0
        private set

    /** 主界面打开过几次；没打开过说明进程是守护叫起来的，事情做完就可以结束。 */
    private var mainCreated = 0

    /** 结束整个进程（只留守护）：先清掉记着的进程号，免得守护以为应用是被强行停止的。 */
    fun exitProcess() {
        Guard.setAppPid(this, 0)
        Process.killProcess(Process.myPid())
    }

    /** 被守护叫起来、又没有界面和前台服务要留着：事情做完结束进程。 */
    fun exitIfIdle() {
        if (mainCreated == 0 && visibleActivities == 0 && !KeepAliveService.running && !backend.busy.value) exitProcess()
    }

    /** 守护叫起来更新通知的收尾：稍等通知发出，调 onFinished，进程是为这个起来的就结束。 */
    fun finishDetached(onFinished: () -> Unit = {}) {
        MainScope().launch {
            delay(1000)
            while (backend.busy.value) delay(200)
            onFinished()
            exitIfIdle()
        }
    }
}

val Context.backend: Backend get() = (applicationContext as App).backend
