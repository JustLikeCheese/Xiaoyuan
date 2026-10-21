package io.github.justlikecheese.xiaoyuan

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justlikecheese.xiaoyuan.portal.PortalState
import io.github.justlikecheese.xiaoyuan.ui.LocalBackend
import io.github.justlikecheese.xiaoyuan.ui.MainScreen
import io.github.justlikecheese.xiaoyuan.ui.XiaoyuanTheme
import io.github.justlikecheese.xiaoyuan.ui.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferHighRefreshRate()
        applyDebugPortal(intent)
        openPage(intent)
        val b = backend
        setContent {
            val settings by b.settingsState.collectAsStateWithLifecycle()
            val dark = isDark(settings.theme, isSystemInDarkTheme())
            // 状态栏、导航栏图标的深浅跟随应用主题（主题可以和系统不一样）
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }
            CompositionLocalProvider(LocalBackend provides b) {
                XiaoyuanTheme(settings.theme, settings.color) { MainScreen(onClose = ::finish) }
            }
        }
    }

    /**
     * 用屏幕支持的最高刷新率（90Hz、120Hz 屏）：ColorOS 等系统默认把没进白名单的应用限制在 60Hz，
     * 应用自己声明想要的显示模式后系统会照顾（省电模式下系统仍可能降下来）。
     */
    private fun preferHighRefreshRate() {
        val d = (if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay) ?: return
        val now = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applyDebugPortal(intent)) backend.refresh()
        openPage(intent)
    }

    /** 从通知点开时切到指定的页（守护通知到自动重连页）。 */
    private fun openPage(intent: Intent?) {
        val page = intent?.getIntExtra(EXTRA_PAGE, -1) ?: -1
        if (page >= 0) backend.showPage(page)
    }

    companion object {
        const val EXTRA_PAGE = "page"
    }

    /**
     * 调试版可以改连本地假门户（同桌面端的 XIAOYUAN_PORTAL 环境变量）：
     *   adb reverse tcp:18080 tcp:18080
     *   adb shell am start -n io.github.justlikecheese.xiaoyuan/.MainActivity --es portal http://127.0.0.1:18080
     * 传空串改回设置里的门户地址。
     */
    private fun applyDebugPortal(intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG) return false
        val portal = intent?.getStringExtra("portal") ?: return false
        backend.portalOverride = portal.ifEmpty { null }
        PortalState.forgetTerminal()
        return true
    }
}
