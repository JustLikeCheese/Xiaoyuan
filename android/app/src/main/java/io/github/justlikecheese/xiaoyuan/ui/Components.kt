package io.github.justlikecheese.xiaoyuan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Tablet
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justlikecheese.xiaoyuan.core.Backend
import io.github.justlikecheese.xiaoyuan.core.FieldView
import io.github.justlikecheese.xiaoyuan.core.MASK
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import io.github.justlikecheese.xiaoyuan.R
import io.github.justlikecheese.xiaoyuan.portal.carrierName
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.layout.Layout

val LocalBackend = staticCompositionLocalOf<Backend> { error("没有提供 Backend") }

/** 切换账号的图标（首页“切换账号”、账号管理里的切换）。 */
val SWITCH_ICON = Icons.Outlined.SwapHoriz

/** 平板、横屏等宽屏：一行放更多数值；手机竖屏两列。 */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

@Composable
fun cardPadding(): Dp = if (isWide()) 24.dp else 16.dp

fun masked(text: String, secret: Boolean, reveal: Boolean): String = when {
    text.isEmpty() -> "-"
    secret && !reveal -> MASK
    else -> text
}

/**
 * 一页：大标题，右侧是 actions 里的按钮和显示/隐藏敏感信息的眼睛（同桌面端窗口右上角），下面是卡片。
 * 页面右下角有悬浮按钮时 bottomPadding 留出位置，免得挡住最后的内容。
 */
@Composable
fun Page(
    title: String,
    scroll: ScrollState,
    spacing: Dp = 12.dp,
    bottomPadding: Dp = 24.dp,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val b = LocalBackend.current
    val revealed by b.revealed.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(scroll, enabled = scroll.maxValue > 0)   // 一屏放得下时不能拖动
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            actions()
            IconButton(onClick = { b.setRevealed(!revealed) }) {
                Icon(
                    if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (revealed) "隐藏敏感信息" else "显示敏感信息（账号、实名、密码、IP、MAC）",
                )
            }
        }
        content()
    }
}

/** 同桌面端 Card.qml：圆角、浅底色、细边框。 */
@Composable
fun XCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    if (onClick != null) {
        Card(onClick, modifier.fillMaxWidth(), shape = shape, colors = colors, border = border, content = content)
    } else {
        Card(modifier.fillMaxWidth(), shape = shape, colors = colors, border = border, content = content)
    }
}

/** 标题 + 数值。点数值即复制（打码时复制的也是真实内容）；secret 项在隐藏敏感信息时打码。 */
@Composable
fun InfoField(title: String, value: String, secret: Boolean = false, modifier: Modifier = Modifier) {
    val b = LocalBackend.current
    val revealed by b.revealed.collectAsStateWithLifecycle()
    Column(modifier) {
        Text(title, fontSize = 12.sp, color = LocalStatusColors.current.textTertiary, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(
            masked(value, secret, revealed),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (value.isNotEmpty()) Modifier.clickable { b.copy(value, title) } else Modifier,
        )
    }
}

/** 一组数值，每行 columns 个，等宽。 */
@Composable
fun FieldGrid(items: List<FieldView>, columns: Int, rowSpacing: Dp = 14.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
        for (row in items.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (f in row) InfoField(f.title, f.value, f.secret, Modifier.weight(1f))
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 页面里分组的小标题。 */
@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp))
}

/** 圆角小标签，比如“在线”。 */
@Composable
fun Pill(text: String, tone: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(tone.copy(alpha = if (LocalStatusColors.current.dark) 0.22f else 0.13f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tone, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** 状态卡片左侧的圆形图标。 */
@Composable
fun StateBadge(icon: ImageVector, color: Color, rotation: Float = 0f, size: Dp = 56.dp) {
    Box(Modifier.size(size).background(color.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.43f).rotate(rotation))
    }
}

// ---------------------------------------------------------------- 设置项（自动重连、设置页）

/** 一组设置：可选的小标题 + 一张卡片，卡片里的行之间用 PrefDivider 隔开。 */
@Composable
fun PrefGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) GroupTitle(title)
        XCard(content = content)
    }
}

@Composable
fun PrefDivider() {
    HorizontalDivider(Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

/** 一行设置：图标、标题、一行说明，右侧放开关等控件；onClick 不为空时整行可点。 */
@Composable
fun PrefRow(
    icon: ImageVector,
    title: String,
    summary: String = "",
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .height(64.dp)   // 所有设置行一样高
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary.isNotEmpty()) {
                Text(summary, fontSize = 12.sp, color = LocalStatusColors.current.textTertiary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

/** 选择型设置：说明处显示当前值，点一下在右侧弹出选项菜单（每项带图标，当前选中的打勾）。 */
@Composable
fun <T> PrefSelect(
    icon: ImageVector,
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    itemIcon: (@Composable (T) -> Unit)? = null,
    summary: String = label(selected),
    trailing: (@Composable RowScope.() -> Unit)? = null,   // 右侧放什么，默认是下拉箭头
) {
    var open by remember { mutableStateOf(false) }
    Box {
        PrefRow(icon, title, summary, onClick = { open = true }) {
            if (trailing != null) trailing()
            else Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DownMenu(open, onDismissRequest = { open = false }) {
            for (o in options) MenuOption(label(o), o == selected, itemIcon?.let { { it(o) } }) {
                open = false
                onSelect(o)
            }
        }
    }
}

/** 菜单的一项：左边图标，右边选中时打勾。 */
@Composable
fun MenuOption(text: String, selected: Boolean, icon: (@Composable () -> Unit)?, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else null) },
        leadingIcon = icon,
        trailingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, contentDescription = "当前", tint = MaterialTheme.colorScheme.primary) }
        } else null,
        onClick = onClick,
    )
}

/**
 * 运营商的小图标：三大运营商用各自的标志（res/drawable/ic_carrier_*，取自官方标志的图形部分），垫一个白色圆底，
 * 深色主题下也看得清；校园网用学校图标。
 */
@Composable
fun CarrierIcon(suffix: String, size: Dp = 24.dp) {
    val logo = when (suffix) {
        "@cmcc" -> R.drawable.ic_carrier_cmcc
        "@unicom" -> R.drawable.ic_carrier_unicom
        "@telecom" -> R.drawable.ic_carrier_telecom
        else -> null
    }
    if (logo == null) {
        Box(Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.School, contentDescription = "校园网", tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(size * 0.7f))
        }
    } else {
        Box(Modifier.size(size).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
            Image(painterResource(logo), contentDescription = carrierName(suffix), modifier = Modifier.size(size * 0.78f))
        }
    }
}

// ---------------------------------------------------------------- 输入

/** 终端类型对应的小图标（账号列表、对话框里用）。 */
fun terminalIcon(type: Int): ImageVector = when (type) {
    1 -> Icons.Outlined.Computer
    2 -> Icons.Outlined.Smartphone
    3 -> Icons.Outlined.Tablet
    4 -> Icons.Outlined.TabletAndroid
    else -> Icons.Outlined.DevicesOther
}

/**
 * 默认打码的输入框。onShownChange 不为空时右侧有小眼睛，点一下显示、再点隐藏；为空时由外面统一控制
 * （比如对话框右上角的眼睛）。
 */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    shown: Boolean,
    onShownChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    imeAction: ImeAction = ImeAction.Default,
    title: String? = null,
    leadingIcon: ImageVector? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        label = title?.let { { Text(it) } },
        leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null) } },
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = onShownChange?.let { change ->
            {
                IconButton(onClick = { change(!shown) }) {
                    Icon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (shown) "隐藏" else "显示")
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Ascii,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        keyboardActions = keyboardActions,
    )
}

/**
 * 下拉框（同桌面端 ComboBox）：菜单和输入框一样宽，当前选中的打勾。selected 为 null 时显示 placeholder。
 * itemIcon 不为空时每一项、以及输入框左侧都显示对应的图标；否则输入框左侧用固定的 leadingIcon。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    title: String? = null,
    leadingIcon: ImageVector? = null,
    itemIcon: (@Composable (T) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = selected?.let(label) ?: "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = title?.let { { Text(it) } },
            leadingIcon = when {
                itemIcon != null && selected != null -> { { itemIcon(selected) } }
                leadingIcon != null -> { { Icon(leadingIcon, contentDescription = null) } }
                else -> null
            },
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier.matchParentSize()
                .clickable(enabled = enabled && options.isNotEmpty(), role = Role.DropdownList) { expanded = true }
                .semantics { contentDescription = listOfNotNull(title, selected?.let(label)).joinToString("：") },
        )
        FieldMenu(expanded, onDismissRequest = { expanded = false }) {
            for (o in options) MenuOption(label(o), o == selected, itemIcon?.let { { it(o) } }) {
                expanded = false
                onSelect(o)
            }
        }
    }
}

/**
 * 下拉菜单，挂在外面的 Box（输入框、设置行）下面，向下展开。位置按屏幕坐标自己算：下面放不全时限制高度、可以滚动；
 * 下面实在太挤（不到 MENU_MIN_BELOW）才放到上面。
 * matchWidth：和 Box 一样宽（输入框）；否则按内容宽、至少 minWidth，右边和 Box 对齐（设置行）。
 * （M3 的菜单下面放不下时会翻到上面、盖住所在的行；在对话框里还会按对话框窗口内的坐标算错位置。）
 */
private val MENU_MIN_BELOW = 160.dp

@Composable
fun BoxScope.FieldMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable () -> Unit) =
    DownMenu(expanded, onDismissRequest, matchWidth = true, content = content)

@Composable
fun BoxScope.DownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    matchWidth: Boolean = false,
    minWidth: Dp = 240.dp,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val margin = with(density) { 12.dp.roundToPx() }
    var anchor by remember { mutableStateOf(IntRect.Zero) }   // 输入框在屏幕上的位置
    Box(Modifier.matchParentSize().onGloballyPositioned { c ->
        val p = c.positionOnScreen()
        anchor = IntRect(IntOffset(p.x.roundToInt(), p.y.roundToInt()), c.size)
    })
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = expanded
    if (!visible.currentState && !visible.targetState) return
    val frame = remember(expanded) { Rect().also { view.getWindowVisibleDisplayFrame(it) } }   // 屏幕上可用的区域（不含状态栏、导航栏、键盘）
    val minBelow = with(density) { MENU_MIN_BELOW.roundToPx() }
    val spaceBelow = frame.bottom - anchor.bottom - margin
    val spaceAbove = anchor.top - frame.top - margin
    // 放下面还是上面在这里定好（不按展开过程中变化的高度判断，免得展开到一半跳到上面）
    val below = spaceBelow >= minBelow || spaceBelow >= spaceAbove
    val maxHeight = if (below) spaceBelow else spaceAbove
    val provider = remember(view, margin, matchWidth, below) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                // anchorBounds 是相对所在窗口（对话框时就是对话框窗口）的坐标，换成屏幕坐标再判断
                val origin = IntArray(2).also { view.rootView.getLocationOnScreen(it) }
                val f = Rect().also { view.getWindowVisibleDisplayFrame(it) }
                val top = anchorBounds.top + origin[1]
                val bottom = anchorBounds.bottom + origin[1]
                val y = if (below) bottom else top - popupContentSize.height
                val x = if (matchWidth) anchorBounds.left else (anchorBounds.right - popupContentSize.width).coerceAtLeast(0)
                return IntOffset(x, y - origin[1])
            }
        }
    }
    // MD3 菜单的级联展开：底板从锚点那一边展开（下面的从上往下，上面的从下往上），菜单项一个接一个淡入、轻轻落到位
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(MENU_REVEAL_MS, easing = LinearEasing))
        }
    }
    val shift = with(density) { 6.dp.toPx() }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(visible, enter = fadeIn(tween(90)), exit = fadeOut(tween(90))) {
            Surface(
                shape = MenuDefaults.shape,
                color = MenuDefaults.containerColor,
                tonalElevation = MenuDefaults.TonalElevation,
                shadowElevation = MenuDefaults.ShadowElevation,
                modifier = with(density) {
                    (if (matchWidth) Modifier.width(anchor.width.toDp()) else Modifier.widthIn(min = minWidth, max = 320.dp))
                        .heightIn(max = maxHeight.coerceAtLeast(margin * 8).toDp())
                }.graphicsLayer {
                    // 底板展开：按进度裁出一部分（展开完就不裁，阴影完整）
                    val f = EmphasizedDecelerate.transform((reveal.value * MENU_REVEAL_MS / CONTAINER_MS).coerceIn(0f, 1f))
                    clip = f < 1f
                    shape = if (below) RevealTop(f) else RevealBottom(f)
                },
            ) {
                StaggerColumn(
                    progress = { reveal.value },
                    shift = shift,
                    fromTop = below,
                    modifier = (if (matchWidth) Modifier else Modifier.width(IntrinsicSize.Max))
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    content = content,
                )
            }
        }
    }
}

// 级联展开的节奏（参考 MD3 菜单动效）：底板 250ms 展开；菜单项每项错开 30ms，各自 180ms 淡入下落
private const val CONTAINER_MS = 250
private const val ITEM_STAGGER_MS = 30
private const val ITEM_MS = 180
private const val MENU_REVEAL_MS = 600
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

private class RevealTop(private val f: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height * f))
}

private class RevealBottom(private val f: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, size.height * (1 - f), size.width, size.height))
}

/**
 * 竖排的菜单项，第 i 项在 i×30ms 后开始淡入、从上方 shift 处落到位（菜单在锚点上面时从下往上排着出来）。
 * 一个进度驱动全部项，不用每项各起一个动画；放置时用图层改透明度和位移，不触发重新测量。
 */
@Composable
private fun StaggerColumn(progress: () -> Float, shift: Float, fromTop: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val width = maxOf(constraints.minWidth, placeables.maxOfOrNull { it.width } ?: 0)
        val height = placeables.sumOf { it.height }
        val n = placeables.size
        layout(width, height) {
            var y = 0
            placeables.forEachIndexed { i, p ->
                val order = if (fromTop) i else n - 1 - i
                p.placeWithLayer(0, y) {
                    val ms = progress() * MENU_REVEAL_MS - order * ITEM_STAGGER_MS
                    val f = EmphasizedDecelerate.transform((ms / ITEM_MS).coerceIn(0f, 1f))
                    alpha = f
                    translationY = (1 - f) * if (fromTop) -shift else shift
                }
                y += p.height
            }
        }
    }
}

/**
 * 右下角的按钮（首页登录 / 注销、账号管理添加、自动重连开关）：圆角方块，展开时右边出文字，收起时只剩图标，
 * 展开、收起有横向动画。宽度跟着内容走；三页用同样的内边距和字号，“添加账号”和“00:00:00”差不多宽，自然对齐。
 */
@Composable
fun XFab(
    onClick: () -> Unit,
    container: Color,
    content: Color,
    icon: @Composable () -> Unit,
    expanded: Boolean,
    text: String,
    textModifier: Modifier = Modifier,
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = container, contentColor = content, shadowElevation = 6.dp) {
        Row(Modifier.height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            AnimatedVisibility(
                visible = expanded,
                enter = expandHorizontally(tween(160, easing = FastOutSlowInEasing), expandFrom = Alignment.Start),
                exit = shrinkHorizontally(tween(140, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Start),
            ) {
                Text(
                    text,
                    modifier = textModifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** 带图标的按钮。primary 为强调按钮（同桌面端 highlighted），flat 为无边框按钮。 */
@Composable
fun IconTextButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    flat: Boolean = false,
) {
    val inner: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1)
    }
    val padding = PaddingValues(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
    when {
        primary -> Button(onClick, modifier, enabled, contentPadding = padding, content = inner)
        flat -> TextButton(onClick, modifier, enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            content = inner)
        else -> OutlinedButton(onClick, modifier, enabled, contentPadding = padding, content = inner)
    }
}
