package io.github.justlikecheese.xiaoyuan.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justlikecheese.xiaoyuan.core.AccountView
import io.github.justlikecheese.xiaoyuan.core.FieldView
import io.github.justlikecheese.xiaoyuan.core.MASK
import io.github.justlikecheese.xiaoyuan.core.SENSITIVE_KEYS
import io.github.justlikecheese.xiaoyuan.core.StatusView
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import android.app.Activity
import androidx.compose.ui.platform.LocalContext

/** 首页的两个对话框开着没有。放在页面外面，切到别的页再回来时还在。 */
@Stable
class HomeUi {
    var showDetails by mutableStateOf(false)
    var cardExpanded by mutableStateOf(false)   // 状态卡片默认只显示已用时长、已用流量
}

/** 怎么称呼一个账号：有备注用“备注 · 运营商”；没备注时眼睛开着显示账号，关着只显示运营商（不显示一串圆点）。 */
fun accountLabel(a: AccountView, reveal: Boolean) = when {
    a.note.isNotEmpty() -> "${a.note} · ${a.carrier}"
    reveal -> "${a.account} · ${a.carrier}"
    else -> a.carrier
}

/** 登录页：状态卡片（含账号信息）和下面左右两个小卡片（连接详情、原始字段，点开是对话框）。 */
@Composable
fun HomePage(ui: HomeUi, scroll: ScrollState, onNavigate: (Int) -> Unit) {
    val b = LocalBackend.current
    val notice by b.noticeText.collectAsStateWithLifecycle()
    val st by b.status.collectAsStateWithLifecycle()
    val details by b.details.collectAsStateWithLifecycle()
    val raw by b.rawJson.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    Page("校园网登录", scroll, bottomPadding = 96.dp, actions = {
        if (notice.isNotEmpty()) {
            IconButton(onClick = b::openNotice) { Icon(Icons.Outlined.Campaign, contentDescription = "门户公告") }
        }
        IconButton(onClick = b::refreshWithToast, enabled = st.state != "querying") {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新状态")
        }
        IconButton(onClick = {
            minimizeApp(activity, b.guard.value, b.reconnect.value.enabled)
        }) {
            Icon(Icons.AutoMirrored.Outlined.ExitToApp, contentDescription = "最小化运行")
        }
    }) {
        StatusCard(ui, onNavigate)
        DashRow {
            TrafficCard(Modifier.weight(1f))
            MemoryCard(Modifier.weight(1f))
        }
        DashRow {
            LinkCard("连接详情", "${details.count { it.value.isNotEmpty() }} 项", Icons.Outlined.Info,
                Modifier.weight(1f)) { ui.showDetails = true }
            ReconnectCard(Modifier.weight(1f)) { onNavigate(2) }
        }
    }
    if (ui.showDetails) DetailsDialog { ui.showDetails = false }
}

@Composable
private fun stateColor(st: StatusView) = LocalStatusColors.current.let {
    when (st.state) {
        "online" -> it.success
        "error" -> it.danger
        else -> it.neutral
    }
}

/** 状态卡片：在线状态、当前账号、IP、用量，门户给了账号信息（姓名、套餐、流量等）时也放在这里。 */
@Composable
private fun StatusCard(ui: HomeUi, onNavigate: (Int) -> Unit) {
    val b = LocalBackend.current
    val st by b.status.collectAsStateWithLifecycle()
    val details by b.details.collectAsStateWithLifecycle()
    val accounts by b.accounts.collectAsStateWithLifecycle()
    val loginKey by b.loginAccount.collectAsStateWithLifecycle()
    val revealed by b.revealed.collectAsStateWithLifecycle()
    val color = stateColor(st)
    val tertiary = LocalStatusColors.current.textTertiary
    // 没在线时告诉用户点“登录”会用哪个账号
    val detail = if (st.state == "offline") {
        accounts.firstOrNull { it.account == loginKey }?.let { "点右下角登录，使用「${accountLabel(it, revealed)}」" }
            ?: "还没有保存密码的账号，点右下角添加"
    } else {
        st.detail
    }
    // 平时只看已用时长、已用流量；展开后显示当前账号、本次在线时长，以及内网 IP、公网 IP（归属地、运营商）
    val usage = listOf(
        FieldView("duration", "已用时长", st.duration, false),
        FieldView("flow", "已用流量", st.flow, false),
    )
    val more = listOf(
        FieldView("account", "当前账号", st.account, true),
        FieldView("actt", "本次在线时长", details.firstOrNull { it.key == "actt" }?.value ?: "", false),
    )
    val rotation by animateFloatAsState(if (ui.cardExpanded) 180f else 0f, tween(200), label = "chevron")

    XCard {
        Column(Modifier.padding(horizontal = cardPadding(), vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StateBadge(if (st.state == "error") Icons.Outlined.Close else Icons.Rounded.Wifi, color)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(st.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = color,
                            modifier = Modifier.weight(1f))
                        SwitchButton(st, onNavigate)
                    }
                    if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, color = tertiary)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { FieldGrid(usage, 2) }
                IconButton(onClick = { ui.cardExpanded = !ui.cardExpanded }) {
                    Icon(Icons.Outlined.ExpandMore, contentDescription = if (ui.cardExpanded) "收起" else "展开更多",
                        modifier = Modifier.rotate(rotation))
                }
            }
            AnimatedVisibility(
                ui.cardExpanded,
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(220)) + fadeOut(tween(150)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FieldGrid(more, 2)
                    IpFields()
                }
            }
        }
    }
}

/** “切换账号”按钮和菜单：点哪个账号就切到哪个（当前在线的那个不能点），最后一项去账号管理。 */
@Composable
private fun SwitchButton(st: StatusView, onNavigate: (Int) -> Unit) {
    val b = LocalBackend.current
    val accounts by b.accounts.collectAsStateWithLifecycle()
    val revealed by b.revealed.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box {
        IconTextButton("切换账号", SWITCH_ICON, { open = true }, flat = true)
        DownMenu(open, onDismissRequest = { open = false }) {
            for (a in accounts) {
                val current = st.online && st.account.lowercase() == a.full.lowercase()
                DropdownMenuItem(
                    text = { Text(accountLabel(a, revealed), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { CarrierIcon(a.isp) },
                    trailingIcon = if (current) {
                        { Icon(Icons.Outlined.Check, contentDescription = "当前在线", tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    enabled = !current,
                    onClick = {
                        open = false
                        b.switchTo(a.account)
                    },
                )
            }
            if (accounts.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (accounts.isNotEmpty()) "管理账号" else "还没有保存的账号，去添加") },
                leadingIcon = { Icon(Icons.Outlined.ManageAccounts, contentDescription = null) },
                onClick = {
                    open = false
                    onNavigate(1)
                },
            )
        }
    }
}

/** 门户公告：打开应用时自动弹出（同一条只弹一次），也可以点右上角的按钮看。 */
@Composable
fun NoticeDialog() {
    val b = LocalBackend.current
    val notice by b.noticeText.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = b::closeNotice,
        icon = { Icon(Icons.Outlined.Campaign, contentDescription = null) },
        title = { Text("门户公告") },
        text = { Text(notice, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = b::closeNotice) { Text("关闭") } },
        dismissButton = {
            Row {
                TextButton(onClick = b::neverShowNotice) { Text("不再显示") }
                TextButton(onClick = b::snoozeNoticeToday) { Text("今天不再显示") }
            }
        },
    )
}

/** 连接详情：上面切换“详情”（整理过的字段）和“原始字段”（chkstatus 的原始 JSON，代码框显示）；两页都能复制。 */
@Composable
private fun DetailsDialog(onDismiss: () -> Unit) {
    val b = LocalBackend.current
    val details by b.details.collectAsStateWithLifecycle()
    val st by b.status.collectAsStateWithLifecycle()
    val raw by b.rawJson.collectAsStateWithLifecycle()
    val revealed by b.revealed.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 眼睛关着时敏感字段打码，复制的也是打码后的内容
    val code = remember(raw, revealed) {
        val shown = if (revealed) raw else JsonObject(raw.mapValues { (k, v) -> if (k in SENSITIVE_KEYS) JsonPrimitive(MASK) else v })
        if (shown.isEmpty()) "" else PRETTY.encodeToString(JsonElement.serializer(), shown)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("连接详情", modifier = Modifier.weight(1f))
                IconButton(onClick = { b.refresh() }, enabled = st.state != "querying") {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("详情", "原始字段").forEachIndexed { i, name ->
                        SegmentedButton(selected = tab == i, onClick = { tab = i },
                            shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(name) }
                    }
                }
                if (tab == 0) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { FieldGrid(details, 2) }
                } else if (code.isEmpty()) {
                    Text("还没有拿到门户的状态", color = LocalStatusColors.current.textTertiary)
                } else {
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))) {
                        SelectionContainer {
                            Text(
                                code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                softWrap = false,
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .horizontalScroll(rememberScrollState())
                                    .padding(12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            // 详情页复制“标题：值”的列表，原始字段页复制 JSON；眼睛关着时复制的也是打码后的内容
            val text = if (tab == 0) {
                details.filter { it.value.isNotEmpty() }.joinToString("\n") { "${it.title}：${masked(it.value, it.secret, revealed)}" }
            } else {
                code
            }
            if (text.isNotEmpty()) TextButton(onClick = { b.copy(text, if (tab == 0) "连接详情" else "原始字段") }) { Text("复制") }
        },
    )
}

private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

/**
 * 首页右下角的开关，照 FlClash 的启动按钮做：颜色始终不变；离线时是方形的“播放”，点一下立刻变成暂停图标并展开，
 * 露出本机记录的已连接时长；在线时点一下立刻收回成播放。请求失败（或没能开始）就退回原来的样子。
 */
@Composable
fun HomeFab() {
    val b = LocalBackend.current
    val busy by b.busy.collectAsStateWithLifecycle()
    val throttle by b.throttle.collectAsStateWithLifecycle()
    val since by b.sessionSince.collectAsStateWithLifecycle()
    // 点下去后先按目标状态显示，请求结束（busy 变回 false）时以实际状态为准；sessionSince 和 busy 在同一次回调里更新，不会闪
    var pending by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(busy) { if (!busy) pending = null }
    val running = pending ?: (since != null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    val progress by animateFloatAsState(if (running) 1f else 0f, tween(160, easing = FastOutSlowInEasing), label = "icon")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val waitAlpha by pulse.animateFloat(1f, 0.45f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "alpha")
    // 收起的过程中保持最后一刻的时长（注销可能比动画先完成，不要先跳成 00:00:00）
    var lastSince by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(since) { if (since != null) lastSince = since }
    val text = when {
        busy && throttle > 0 -> "排队 $throttle 秒"
        since != null -> formatElapsed(now - since!!)
        !running -> formatElapsed(lastSince?.let { now - it } ?: 0)
        else -> formatElapsed(0)
    }

    XFab(
        onClick = {
            // 请求进行中也能点：还没发出去的直接撤销，已经发出去的接着发反向的请求
            if (running) {
                pending = false
                b.logout()
            } else {
                pending = true
                b.loginDefault()
            }
            if (!b.busy.value) pending = null   // 没能开始（比如还没有账号），不改样子
        },
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        icon = { PlayPauseIcon(progress, Modifier.size(24.dp)) },
        expanded = running,
        text = text,
        textModifier = Modifier.alpha(if (busy) waitAlpha else 1f),
    )
}

/**
 * 播放 / 暂停图标的形变（同 FlClash 用的 AnimatedIcons.play_pause）：progress 为 0 是向右的三角，1 是两道竖条；
 * 三角劈成左右两半，各自的四个顶点线性过渡到竖条的四个角，过渡中间略微转一下。
 */
@Composable
fun PlayPauseIcon(progress: Float, modifier: Modifier) {
    val color = LocalContentColor.current
    // 24 格坐标；左半、右半各四个点（右半的三角尖用两个重合的点补成四边形）
    val play = arrayOf(
        floatArrayOf(8f, 5f, 13.5f, 8.1f, 13.5f, 15.9f, 8f, 19f),
        floatArrayOf(13.5f, 8.1f, 19f, 12f, 19f, 12f, 13.5f, 15.9f),
    )
    val pause = arrayOf(
        floatArrayOf(6f, 5f, 10f, 5f, 10f, 19f, 6f, 19f),
        floatArrayOf(14f, 5f, 18f, 5f, 18f, 19f, 14f, 19f),
    )
    Canvas(modifier) {
        val s = size.minDimension / 24f
        rotate(-25f * sin(PI.toFloat() * progress)) {
            for (k in 0..1) {
                val path = Path()
                for (i in 0 until 4) {
                    val x = (play[k][i * 2] + (pause[k][i * 2] - play[k][i * 2]) * progress) * s
                    val y = (play[k][i * 2 + 1] + (pause[k][i * 2 + 1] - play[k][i * 2 + 1]) * progress) * s
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, color)
            }
        }
    }
}

/** 已连接时长：时:分:秒，都补两位（同 FlClash）。 */
fun formatElapsed(ms: Long): String {
    val s = maxOf(0L, ms / 1000)
    return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
}
