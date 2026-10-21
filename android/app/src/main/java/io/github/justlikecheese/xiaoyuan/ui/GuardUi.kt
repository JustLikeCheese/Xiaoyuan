package io.github.justlikecheese.xiaoyuan.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Adb
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justlikecheese.xiaoyuan.core.Guard
import io.github.justlikecheese.xiaoyuan.core.GuardView
import io.github.justlikecheese.xiaoyuan.core.guardModeName
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.widget.Toast
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.PhoneAndroid
import io.github.justlikecheese.xiaoyuan.App

// 常见的图标：前台服务是“手机上常驻”，Root 用 su 常见的“#”，ADB 用安卓调试的小机器人
private fun guardIcon(mode: String): ImageVector = when (mode) {
    Guard.ROOT -> Icons.Outlined.Tag
    Guard.ADB -> Icons.Outlined.Adb
    else -> Icons.Outlined.PhoneAndroid
}

private fun guardStateText(state: String) = when (state) {
    "online" -> "在线"
    "offline" -> "不在线"
    "unreachable" -> "连不上门户"
    "wifi-down" -> "Wi-Fi 断开"
    "wifi-off" -> "Wi-Fi 已关闭"
    "other" -> "连的不是校园网"
    else -> "检测中"
}

/** 守护状态一行：在跑时说最近一次检测的结果，没在跑时说怎么启动。 */
fun guardSummary(g: GuardView): String = when {
    g.running -> "守护运行中 · ${guardStateText(g.state)}" + (if (g.pingMs.isNotEmpty()) " · ping ${g.pingMs} ms" else "")
    g.mode == Guard.ADB -> "守护没在运行，点这里查看启动命令"
    else -> "守护没在运行，点这里重新启动"
}

/** 自动重连页：选保活方式（前台服务 / Root / ADB），用守护时下面多一行状态，点开是详情。 */
@Composable
fun GuardRows() {
    val b = LocalBackend.current
    val g by b.guard.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf(false) }
    // 页面开着时每 5 秒读一次守护的状态和记录（只是读一个 2 KB 的小文件）
    LaunchedEffect(g.mode) {
        while (g.mode != Guard.SERVICE) {
            b.refreshGuard()
            b.refreshGuardEvents()
            delay(5000)
        }
    }
    PrefSelect(
        Icons.Outlined.Shield, "保活方式", Guard.MODES, g.mode, { guardModeName(it) },
        { mode ->
            b.setGuardMode(mode) { ok, msg ->
                if (!ok) b.showToast("error", "${guardModeName(mode)} 不可用", msg, 6000)
                else if (mode == Guard.ADB) details = true   // ADB 要用户自己执行命令
                else if (mode != Guard.SERVICE) b.showToast("success", "已改用 ${guardModeName(mode)} 守护", "应用退出后只留约 0.6 MB 的守护程序")
            }
        },
        itemIcon = { Icon(guardIcon(it), contentDescription = null) },
        // 守护在不在跑也写在这里（检测结果在下面的记录里）；ADB 没在跑时再选一次 ADB 会弹出启动命令
        summary = guardModeName(g.mode) + " · " + when {
            g.mode == Guard.SERVICE -> "划掉后台会被系统停止"   // ColorOS 等划掉就强行停止，前台服务也挡不住
            g.running -> "守护运行中，约 0.6 MB"
            g.mode == Guard.ADB -> "守护没在运行，再选一次 ADB 看命令"
            else -> "守护没在运行"
        },
    )
    if (details) GuardDialog { details = false }
}

private val CHECK_TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** 守护详情：状态；ADB 方式给出启动命令，Root 没在跑时可以重新启动。记录在“重连记录”里。 */
@Composable
private fun GuardDialog(onDismiss: () -> Unit) {
    val b = LocalBackend.current
    val g by b.guard.collectAsStateWithLifecycle()
    val tones = LocalStatusColors.current
    LaunchedEffect(Unit) {
        while (true) {
            b.refreshGuard()
            delay(3000)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(guardIcon(g.mode), contentDescription = null) },
        title = { Text("${guardModeName(g.mode)} 守护") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when {
                        g.running -> guardSummary(g) + if (g.at > 0) "（${CHECK_TIME.format(Instant.ofEpochSecond(g.at))}）" else ""
                        g.mode == Guard.ADB -> "守护没在运行：在电脑上执行下面的命令启动"
                        else -> "守护没在运行：点下面的“重新启动”"
                    },
                    color = if (g.running) MaterialTheme.colorScheme.onSurface else tones.warning,
                )
                Text("Wi-Fi 断开会自动重连，定时 ping 门户、检测在线状态，掉线时临时拉起应用登录，登录完应用自动退出。" +
                    "守护跟随自动重连页右下角的按钮启停，记录在“重连记录”里。",
                    fontSize = 12.sp, color = tones.textTertiary)
                if (g.mode == Guard.ADB) {
                    val cmd = b.guardAdbCommand()
                    Text("在电脑上执行（手机重启后要再执行一次）：", fontSize = 13.sp)
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                            .padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SelectionContainer(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        IconButton(onClick = { b.copy(cmd, "启动命令") }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制命令")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = if (!g.running && g.mode == Guard.ROOT) {
            {
                TextButton(onClick = {
                    b.restartGuard { ok ->
                        if (ok) b.showToast("success", "守护已重新启动")
                        else b.showToast("error", "启动失败", "请在 Magisk 里允许本应用", 6000)
                    }
                }) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  重新启动")
                }
            }
        } else null,
    )
}

/**
 * 自动重连页右下角的按钮，和首页的样子一样、换了颜色：点一下开 / 关自动重连（用守护方式时守护跟着启停），
 * 开着时展开显示已经守了多久。
 */
@Composable
fun ReconnectFab() {
    val b = LocalBackend.current
    val r by b.reconnect.collectAsStateWithLifecycle()
    val toggle = rememberReconnectToggle()
    val on = r.enabled
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(on, r.since) {
        while (on) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    val progress by animateFloatAsState(if (on) 1f else 0f, tween(160, easing = FastOutSlowInEasing), label = "icon")
    XFab(
        onClick = { toggle(!on) },
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        icon = { PlayPauseIcon(progress, Modifier.size(24.dp)) },
        expanded = on,
        text = if (r.state == "paused") "已暂停" else formatElapsed(if (r.since > 0) now - r.since else 0),
    )
}

/**
 * 首页右上角“最小化运行”，按有没有保活分三种：
 *  - 没开自动重连：没东西要保活，整个应用退出；
 *  - 守护在跑：结束整个应用进程，只留守护，提示守护实际占的内存；
 *  - 前台服务保活（或守护没在跑、前台服务在兜底）：服务要留着，只关界面。
 * 要结束进程的两种直接结束，不走“关任务回桌面”：回桌面的过渡由桌面自己做（缩回图标，很柔），会盖掉应用设的动画；
 * 进程直接结束时系统用的是快速消失的过渡，干脆。提示用系统 Toast，进程结束后照样显示完。
 */
var KILL_DELAY_MS = 60L

fun minimizeApp(activity: Activity?, g: GuardView, reconnectOn: Boolean) {
    activity ?: return
    val kill = !reconnectOn || (g.mode != Guard.SERVICE && g.running)
    val text = when {
        !reconnectOn -> "已退出程序"
        kill -> "已最小化，保活进程仅占 " + (if (g.pssKb > 0) "%.1f MB".format(g.pssKb / 1024.0) else "不到 1 MB")
        else -> "已最小化，前台服务继续保活"
    }
    Toast.makeText(activity.applicationContext, text, Toast.LENGTH_SHORT).show()
    activity.finishAndRemoveTask()
    // 关任务后桌面开始绘制，这时马上结束进程：桌面“缩回图标”的柔动画被截断，桌面已经画好也不会黑屏
    if (kill) Handler(Looper.getMainLooper()).postDelayed({ (activity.application as App).exitProcess() }, KILL_DELAY_MS)
}
