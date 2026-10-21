package io.github.justlikecheese.xiaoyuan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justlikecheese.xiaoyuan.core.DeviceStats
import io.github.justlikecheese.xiaoyuan.core.MASK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * 首页下面的小卡片，样式照 FlClash 的仪表盘：顶部一行图标 + 标题，下面是内容。
 * 流量统计、内存信息、连接详情、自动重连；IP（放在状态卡片里）跟随右上角眼睛打码，点 IP 复制。
 */

/** 卡片两两一行，同一行一样高。 */
@Composable
fun DashRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun DashCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    XCard(modifier.fillMaxHeight(), onClick = onClick) {
        Column(Modifier.fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
                trailing()
            }
            content()
        }
    }
}

/** 内网 IP 和公网 IP（含归属地、运营商），左右两栏，放在首页状态卡片展开的部分；“公网”旁边的小按钮重新查。 */
@Composable
fun IpFields() {
    val b = LocalBackend.current
    val revealed by b.revealed.collectAsStateWithLifecycle()
    val local by b.localIp.collectAsStateWithLifecycle()
    val p by b.publicIp.collectAsStateWithLifecycle()
    val tertiary = LocalStatusColors.current.textTertiary
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("内网 IP", fontSize = 12.sp, color = tertiary)
            SmallValue(local, "内网 IP", "未连接 Wi-Fi")
            Text(if (revealed) "Wi-Fi" else MASK, fontSize = 12.sp, color = tertiary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("公网 IP", fontSize = 12.sp, color = tertiary)
                Box(Modifier.size(18.dp).clickable(enabled = !p.loading, onClick = b::loadPublicIp), contentAlignment = Alignment.Center) {
                    if (p.loading) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    else Icon(Icons.Outlined.Refresh, contentDescription = "重新查询公网 IP", modifier = Modifier.size(14.dp), tint = tertiary)
                }
            }
            SmallValue(p.ip, "公网 IP", p.hint)
            // 归属地、运营商也算位置信息，眼睛关着时不显示
            val where = listOf(p.place, p.isp).filter { it.isNotEmpty() }.joinToString(" · ")
            Text(if (where.isEmpty()) " " else if (revealed) where else MASK, fontSize = 12.sp, color = tertiary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 和 InfoField 一样的字号；眼睛关着时打码，点一下复制。 */
@Composable
private fun SmallValue(value: String, title: String, placeholder: String) {
    val b = LocalBackend.current
    val revealed by b.revealed.collectAsStateWithLifecycle()
    Text(
        if (value.isEmpty()) placeholder.ifEmpty { "-" } else masked(value, true, revealed),
        fontSize = 14.sp,
        fontWeight = if (value.isEmpty()) null else FontWeight.SemiBold,
        color = if (value.isEmpty()) LocalStatusColors.current.textTertiary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (value.isNotEmpty()) Modifier.clickable { b.copy(value, title) } else Modifier,
    )
}

/** 首页的自动重连：开关直接在这里切；点卡片去自动重连页看记录、改间隔。 */
@Composable
fun ReconnectCard(modifier: Modifier, onOpen: () -> Unit) {
    val b = LocalBackend.current
    val r by b.reconnect.collectAsStateWithLifecycle()
    val tones = LocalStatusColors.current
    val (state, color) = when (r.state) {
        "watching" -> "监控中" to tones.success
        "reconnecting" -> "正在重连…" to tones.accent
        "paused" -> "已暂停" to tones.warning
        else -> "未开启" to tones.textTertiary
    }
    DashCard("自动重连", Icons.Outlined.Sync, modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color, maxLines = 1)
                if (r.count > 0) {
                    Text("已重连 ${r.count} 次", fontSize = 12.sp, color = tones.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Switch(checked = r.enabled, onCheckedChange = rememberReconnectToggle())
        }
    }
}

/** 字节数拆成数值和单位，单位靠右（同 FlClash）。 */
fun splitBytes(n: Long): Pair<String, String> = when {
    n < 1024 -> n.toString() to "B"
    n < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f", n / 1024.0) to "KB"
    n < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f", n / 1024.0 / 1024) to "MB"
    else -> String.format(Locale.ROOT, "%.2f", n / 1024.0 / 1024 / 1024) to "GB"
}

/** 本次连接以来本机 Wi-Fi 的上传、下载：左边环形图看比例，下面两行是总量。界面开着时每秒更新。 */
@Composable
fun TrafficCard(modifier: Modifier) {
    val b = LocalBackend.current
    var up by remember { mutableLongStateOf(0L) }
    var down by remember { mutableLongStateOf(0L) }
    val since by b.sessionSince.collectAsStateWithLifecycle()
    LaunchedEffect(since) {   // 登录、注销时立刻重算，不等下一秒
        while (true) {
            val (u, d) = withContext(Dispatchers.Default) { b.sessionTraffic() }
            up = u
            down = d
            delay(1000)
        }
    }
    val upColor = MaterialTheme.colorScheme.primary
    val downColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    DashCard("流量统计", Icons.Outlined.DataUsage, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Donut(up, down, upColor, downColor, Modifier.size(56.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Legend(upColor, "上传")
                Legend(downColor, "下载")
            }
        }
        Spacer(Modifier.weight(1f))
        TrafficLine(Icons.Outlined.ArrowUpward, up)
        TrafficLine(Icons.Outlined.ArrowDownward, down)
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(16.dp).height(8.dp).background(color, RoundedCornerShape(4.dp)))
        Text(text, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun TrafficLine(icon: ImageVector, bytes: Long) {
    val (value, unit) = splitBytes(bytes)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(unit, fontSize = 13.sp, color = LocalStatusColors.current.textTertiary)
    }
}

/** 环形图：上传、下载各占一段，中间留缝、圆头（同 FlClash）。都为 0 时各占一半。 */
@Composable
private fun Donut(up: Long, down: Long, upColor: Color, downColor: Color, modifier: Modifier) {
    val total = up + down
    val upShare = if (total == 0L) 0.5f else (up.toFloat() / total).coerceIn(0f, 1f)
    Canvas(modifier) {
        val stroke = size.minDimension * 0.16f
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val gap = 28f   // 两段之间的缝（度），圆头会占掉一部分
        val upSweep = (360f - gap * 2) * upShare
        val downSweep = (360f - gap * 2) * (1 - upShare)
        val style = Stroke(stroke, cap = StrokeCap.Round)
        if (downSweep > 0.5f) drawArc(downColor, -90f + gap / 2 + upSweep + gap, downSweep, false, Offset(inset, inset), arcSize, style = style)
        if (upSweep > 0.5f) drawArc(upColor, -90f + gap / 2, upSweep, false, Offset(inset, inset), arcSize, style = style)
    }
}

/** 本应用占用的内存，每 2 秒采一次，下面画最近一分钟的走势。 */
@Composable
fun MemoryCard(modifier: Modifier) {
    val history by memoryHistory.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        while (true) {
            val m = withContext(Dispatchers.Default) { DeviceStats.appMemory() }
            memoryHistory.value = (memoryHistory.value + m).takeLast(30)
            delay(2000)
        }
    }
    val (value, unit) = splitBytes(history.lastOrNull() ?: 0L)
    DashCard("内存信息", Icons.Outlined.Memory, modifier) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (history.isEmpty()) "-" else value, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(unit, fontSize = 13.sp, color = LocalStatusColors.current.textTertiary, modifier = Modifier.padding(bottom = 2.dp))
        }
        Text("本应用占用", fontSize = 12.sp, color = LocalStatusColors.current.textTertiary)
        Sparkline(history, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp))
    }
}

// 内存走势放在进程里：切换页面再回来还在，不用重新攒点（原来要等两次采样、两秒多才出线）
private val memoryHistory = MutableStateFlow<List<Long>>(emptyList())

/** 走势线：按最小、最大值缩放，线下面淡淡地填色；只有一个点时先画一条平线。 */
@Composable
private fun Sparkline(points: List<Long>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val values = if (points.size == 1) points + points else points
        if (values.size < 2 || size.height < 4f) return@Canvas
        val lo = values.min().toFloat()
        val hi = values.max().toFloat()
        val span = maxOf(hi - lo, hi * 0.2f, 1f)   // 至少按最大值的 20% 画，几 MB 的抖动不会被拉满
        val stepX = size.width / (values.size - 1)
        fun y(v: Long) = size.height - (v - lo) / span * (size.height * 0.8f) - size.height * 0.1f
        val line = Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(i * stepX, y(v)) }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent)))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** 连接详情、原始字段：点开是对话框。 */
@Composable
fun LinkCard(title: String, summary: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    DashCard(title, icon, modifier, onClick = onClick, trailing = {
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }) {
        Text(summary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
