package io.github.justlikecheese.xiaoyuan.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justlikecheese.xiaoyuan.core.RECONNECT_MAX_FAILURES
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.app.NotificationManager
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import io.github.justlikecheese.xiaoyuan.core.HistoryItem
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape

private val UNITS = listOf("秒" to 1, "分钟" to 60, "小时" to 3600)
private val INTERVAL_PRESETS = listOf(10, 30, 60, 120, 300, 600, 1800)
private const val CUSTOM_INTERVAL = -1

private fun intervalText(seconds: Int): String = when {
    seconds % 3600 == 0 -> "${seconds / 3600} 小时"
    seconds % 60 == 0 -> "${seconds / 60} 分钟"
    else -> "$seconds 秒"
}

private fun unitFor(s: Int) = if (s % 3600 == 0) 3600 else if (s % 60 == 0) 60 else 1

private fun lastStateText(s: String) = when (s) {
    "online" -> "在线"
    "offline" -> "离线"
    "unreachable" -> "连不上门户"
    else -> ""
}

/** 自动重连：网络变化时立即检测、平时按兜底间隔检测，掉线后自动用保存的账号重新登录。 */
@Composable
fun ReconnectPage(scroll: ScrollState) {
    val b = LocalBackend.current
    val r by b.reconnect.collectAsStateWithLifecycle()
    val history by b.reconnectHistory.collectAsStateWithLifecycle()
    val guardEvents by b.guardEvents.collectAsStateWithLifecycle()
    // 应用自己的记录和守护的记录按时间合在一起（新的在前；时间是“月-日 时:分:秒”，同一年里按字符串排就对）
    val records = remember(history, guardEvents) { (history + guardEvents).sortedByDescending { it.time } }
    val accounts by b.accounts.collectAsStateWithLifecycle()
    val revealed by b.revealed.collectAsStateWithLifecycle()
    val tones = LocalStatusColors.current
    var editInterval by rememberSaveable { mutableStateOf(false) }
    var showPerms by rememberSaveable { mutableStateOf(false) }
    val perms = rememberPermissions()
    val setEnabled = rememberReconnectToggle()
    val stateColor = when (r.state) {
        "watching" -> tones.success
        "reconnecting" -> tones.accent
        "paused" -> tones.warning
        else -> tones.neutral
    }
    // 可选的重连账号：最近使用 + 保存了密码的账号
    val accountOptions = listOf("最近使用的账号" to "") + accounts.filter { it.passwordState == "saved" }
        .map { accountLabel(it, revealed) to it.account }

    Page("自动重连", scroll, bottomPadding = 96.dp) {
        // ---------------------------------------------------------------- 状态
        XCard {
            Row(
                Modifier.padding(horizontal = cardPadding(), vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val spin = rememberInfiniteTransition(label = "spin")
                val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
                    label = "angle")
                StateBadge(if (r.state == "paused") Icons.Outlined.Pause else Icons.Outlined.Sync, stateColor,
                    if (r.state == "reconnecting") angle else 0f, size = 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        when (r.state) {
                            "watching" -> "监控中"
                            "reconnecting" -> "正在重连…"
                            "paused" -> "已暂停"
                            else -> "未开启"
                        },
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = stateColor,
                    )
                    Text(
                        when (r.state) {
                            "off" -> "掉线后自动用保存的账号登录"
                            "paused" -> "手动注销或连续失败后暂停"
                            else -> "上次检测 ${r.lastCheck.ifEmpty { "-" }}" +
                                (if (r.lastState.isNotEmpty()) "（${lastStateText(r.lastState)}）" else "")
                        },
                        fontSize = 12.sp, color = tones.textTertiary,
                    )
                }
                if (r.state == "paused") {
                    IconTextButton("恢复", Icons.Outlined.PlayArrow, b::resumeReconnect, primary = true)
                } else {
                    InfoField("已重连", "${r.count} 次")
                }
            }
        }

        PermissionWarning(missingEssential(perms)) { showPerms = true }

        // ---------------------------------------------------------------- 设置
        PrefGroup {
            // 网络变化时会立即检测，这里是定时检测的间隔；常用值直接选，其他的点“自定义”
            val intervals = (INTERVAL_PRESETS + r.interval).distinct().sorted() + CUSTOM_INTERVAL
            PrefSelect(Icons.Outlined.Timer, "掉线检测间隔", intervals, r.interval,
                { if (it == CUSTOM_INTERVAL) "自定义…" else intervalText(it) },
                { if (it == CUSTOM_INTERVAL) editInterval = true else b.setReconnectInterval(it) },
                itemIcon = { Icon(if (it == CUSTOM_INTERVAL) Icons.Outlined.Edit else Icons.Outlined.Timer, contentDescription = null) },
                summary = "每 ${r.intervalText}一次" + if (r.networkEvents) "，网络变化时立即检测" else "")
            PrefDivider()
            val chosen = accountOptions.firstOrNull { it.second == r.account } ?: accountOptions[0]
            val optionIcon: @Composable (Pair<String, String>) -> Unit = { o ->
                val isp = accounts.firstOrNull { it.account == o.second }?.isp
                if (isp == null) Icon(Icons.Outlined.History, contentDescription = null) else CarrierIcon(isp)
            }
            PrefSelect(Icons.Outlined.People, "重连账号", accountOptions, chosen, { it.first },
                { b.setReconnectAccount(it.second) },
                itemIcon = optionIcon,
                summary = "掉线时用它重新登录",
                trailing = {
                    // 右侧直接放选中的账号
                    Row(
                        Modifier.padding(end = 4.dp).widthIn(max = 160.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { optionIcon(chosen) }
                        Text(chosen.first, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                })
            PrefDivider()
            GuardRows()
        }

        // ---------------------------------------------------------------- 记录
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupTitle("重连记录", Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = b::clearReconnectHistory, enabled = records.isNotEmpty()) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空记录")
            }
        }
        XCard {
            Column(Modifier.padding(vertical = 6.dp)) {
                var lastDay = ""
                records.forEach { h ->
                    val day = h.time.substringBefore(' ')
                    if (day != lastDay) {
                        lastDay = day
                        Text(dayLabel(day), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp))
                    }
                    RecordRow(h)
                }
                if (records.isEmpty()) {
                    Text("还没有记录。开启后，检测结果和每次重连都记在这里。", color = tones.textTertiary,
                        modifier = Modifier.padding(16.dp))
                }
            }
        }
    }

    if (editInterval) IntervalDialog(r.interval, { b.setReconnectInterval(it) }) { editInterval = false }
    if (showPerms) PermissionsDialog { showPerms = false }
}

private val DAY = java.time.format.DateTimeFormatter.ofPattern("MM-dd")

/** 记录的日期（“月-日”）显示成“今天”“昨天”或日期。 */
private fun dayLabel(day: String): String {
    val today = java.time.LocalDate.now()
    return when (day) {
        today.format(DAY) -> "今天"
        today.minusDays(1).format(DAY) -> "昨天"
        else -> day
    }
}

/** 一条记录：左边圆底图标（颜色表示结果），中间标题和详情、来源，右边时间。 */
@Composable
private fun RecordRow(h: HistoryItem) {
    val tones = LocalStatusColors.current
    val (icon, color) = when (h.kind) {
        "success" -> Icons.Outlined.CheckCircle to tones.success
        "error" -> Icons.Outlined.ErrorOutline to tones.danger
        "warning" -> Icons.Outlined.WarningAmber to tones.warning
        else -> Icons.Outlined.Info to tones.accent
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(30.dp).background(color.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(h.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(h.detail, h.source).filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 12.sp,
                color = tones.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(h.time.substringAfter(' '), fontSize = 12.sp, color = tones.textTertiary,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
    }
}

/** 检测间隔：数字 + 单位（秒 / 分钟 / 小时），后端会夹到 5 秒~24 小时。 */
@Composable
private fun IntervalDialog(interval: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var unit by remember { mutableIntStateOf(unitFor(interval)) }
    var text by remember { mutableStateOf((interval / unit).toString()) }
    val value = text.toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("掉线检测间隔") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    label = { Text("间隔") },
                    leadingIcon = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                    suffix = { Text(UNITS.first { it.second == unit }.first) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    UNITS.forEachIndexed { i, (name, seconds) ->
                        SegmentedButton(
                            selected = unit == seconds,
                            onClick = { unit = seconds },
                            shape = SegmentedButtonDefaults.itemShape(i, UNITS.size),
                        ) { Text(name) }
                    }
                }
                Text("网络变化时会立即检测，这里是定时检测的间隔；最短 5 秒，连续失败 $RECONNECT_MAX_FAILURES 次会暂停",
                    fontSize = 12.sp, color = LocalStatusColors.current.textTertiary)
            }
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = {
                onSave(value!! * unit)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}


/**
 * 打开 / 关闭自动重连。打开时会在通知栏常驻（前台服务），安卓 13 起要先拿到通知权限；
 * 拿不到也照样打开，只是看不到通知。
 */
@Composable
fun rememberReconnectToggle(): (Boolean) -> Unit {
    val b = LocalBackend.current
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { b.setReconnectEnabled(true) }
    return { on ->
        val need = on && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (need) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else b.setReconnectEnabled(on)
    }
}
