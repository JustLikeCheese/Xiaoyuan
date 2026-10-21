package io.github.justlikecheese.xiaoyuan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justlikecheese.xiaoyuan.core.Toast
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private class NavItem(val title: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val NAV = listOf(
    NavItem("登录", Icons.Outlined.Home, Icons.Filled.Home),
    NavItem("账号管理", Icons.Outlined.People, Icons.Filled.People),
    NavItem("自动重连", Icons.Outlined.Sync, Icons.Filled.Sync),
    NavItem("设置", Icons.Outlined.Settings, Icons.Filled.Settings),
)

private class ToastVisuals(val toast: Toast) : SnackbarVisuals {
    override val message = toast.title
    override val actionLabel: String? = null
    override val withDismissAction = true
    override val duration = SnackbarDuration.Indefinite
}

/** 主界面：四个页面（手机上导航在底部，平板上在左侧）、右下角的悬浮按钮、提示条和各个对话框。 */
@Composable
fun MainScreen(onClose: () -> Unit) {
    val b = LocalBackend.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    val scrolls = List(NAV.size) { rememberScrollState() }
    val home = remember { HomeUi() }
    val accountsUi = remember { AccountsUi() }
    val snackbar = remember { SnackbarHostState() }
    val accounts by b.accounts.collectAsStateWithLifecycle()
    val noticePopup by b.noticePopup.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { b.startup() }
    // 界面在前台时定时刷新状态，切到后台停
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> b.setForeground(true)
                Lifecycle.Event.ON_STOP -> b.setForeground(false)
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            b.setForeground(false)
        }
    }
    LaunchedEffect(Unit) {
        b.editRequested.collect {
            page = 1
            accountsUi.editing = it
        }
    }
    LaunchedEffect(Unit) { b.pageRequested.collect { page = it; b.pageShown() } }
    LaunchedEffect(Unit) { b.closeRequested.collect { onClose() } }
    // 提示依次显示，各自按自己的时长消失（同桌面端 ToastHost）
    LaunchedEffect(Unit) {
        b.toasts.collect { t -> withTimeoutOrNull(t.durationMs) { snackbar.showSnackbar(ToastVisuals(t)) } }
    }
    BackHandler(enabled = page != 0) { page = 0 }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            NAV.forEachIndexed { i, item ->
                item(
                    selected = page == i,
                    onClick = { page = i },
                    icon = { Icon(if (page == i) item.selectedIcon else item.icon, contentDescription = null) },
                    label = { Text(item.title) },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            val slide = with(LocalDensity.current) { 24.dp.roundToPx() }
            // 切页：新页面从下方 24dp 滑入并淡入，220ms
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    (fadeIn(tween(180, easing = EaseOutCubic)) + slideInVertically(tween(220, easing = EaseOutCubic)) { slide })
                        .togetherWith(ExitTransition.None)
                },
                label = "page",
            ) { p ->
                when (p) {
                    0 -> HomePage(home, scrolls[0]) { page = it }
                    1 -> AccountsPage(accountsUi, scrolls[1])
                    2 -> ReconnectPage(scrolls[2])
                    else -> SettingsPage(scrolls[3])
                }
            }
            val hasFab = page == 0 || page == 1 || page == 2
            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                when (page) {
                    0 -> HomeFab()
                    1 -> AddAccountFab(accountsUi)
                    2 -> ReconnectFab()
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (hasFab) 72.dp else 0.dp)) { data ->
                ToastBar((data.visuals as ToastVisuals).toast, data::dismiss)
            }
        }
    }

    if (noticePopup) NoticeDialog()
    accountsUi.editing?.let { key ->
        AccountDialog(
            acc = accounts.firstOrNull { it.account == key && key.isNotEmpty() },
            onDismiss = { accountsUi.editing = null },
            onDelete = { accountsUi.deleting = it },
        )
    }
    accountsUi.deleting?.let { key ->
        val acc = accounts.firstOrNull { it.account == key }
        val revealed by b.revealed.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { accountsUi.deleting = null },
            title = { Text("删除账号") },
            text = { Text("确定删除「${acc?.let { accountLabel(it, revealed) } ?: key}」？保存的密码也会一起删除。") },
            confirmButton = {
                TextButton(onClick = {
                    b.deleteAccount(key)
                    accountsUi.deleting = null
                    accountsUi.editing = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { accountsUi.deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ToastBar(t: Toast, onDismiss: () -> Unit) {
    val tones = LocalStatusColors.current
    val (icon, tint, bg) = when (t.kind) {
        "success" -> Triple(Icons.Outlined.CheckCircle, tones.success, tones.successBg)
        "error" -> Triple(Icons.Outlined.ErrorOutline, tones.danger, tones.dangerBg)
        "offline" -> Triple(Icons.Outlined.PowerSettingsNew, tones.danger, tones.dangerBg)   // 已注销：红色
        "warning" -> Triple(Icons.Outlined.WarningAmber, tones.warning, tones.warningBg)
        else -> Triple(Icons.Outlined.Info, tones.accent, tones.infoBg)
    }
    Snackbar(
        modifier = Modifier.padding(12.dp),
        containerColor = bg,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dismissAction = {
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp)) }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Column {
                Text(t.title, fontWeight = FontWeight.SemiBold)
                if (t.content.isNotEmpty()) Text(t.content, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
