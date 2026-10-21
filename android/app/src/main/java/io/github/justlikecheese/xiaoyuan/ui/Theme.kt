package io.github.justlikecheese.xiaoyuan.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 状态色，取值同桌面端 Theme.qml（成功绿、危险红、警告黄、中性灰），提示条底色也一样。 */
data class StatusColors(
    val dark: Boolean,
    val success: Color,
    val danger: Color,
    val warning: Color,
    val neutral: Color,
    val accent: Color,
    val textTertiary: Color,
    val successBg: Color,
    val dangerBg: Color,
    val warningBg: Color,
    val infoBg: Color,
)

val LocalStatusColors = staticCompositionLocalOf<StatusColors> { error("没有提供 StatusColors") }

fun isDark(theme: String, system: Boolean) = when (theme) {
    "Light" -> false
    "Dark" -> true
    else -> system
}

/** 主题颜色：dynamic 为跟随壁纸取色（安卓 12 起），其余为种子色，默认是图标的蓝色。 */
val THEME_COLORS: List<Pair<String, String>> = buildList {
    if (Build.VERSION.SDK_INT >= 31) add("dynamic" to "壁纸取色")
    add("#366092" to "校园蓝")
    add("#0F7C8C" to "青色")
    add("#3E7D3A" to "绿色")
    add("#6750A4" to "紫色")
    add("#B24A6E" to "粉色")
    add("#B5651D" to "橙色")
}

fun seedOf(color: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(color)) }.getOrDefault(Color(0xFF366092))

/** theme：Light / Dark / Auto；color：dynamic（壁纸取色，同桌面端跟随 Windows 强调色）或 #RRGGBB 种子色。 */
@Composable
fun XiaoyuanTheme(theme: String, color: String, content: @Composable () -> Unit) {
    val dark = isDark(theme, isSystemInDarkTheme())
    val context = LocalContext.current
    val scheme = remember(color, dark) {
        if (color == "dynamic" && Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            seedColorScheme(seedOf(if (color == "dynamic") "#366092" else color), dark)
        }
    }
    val tones = StatusColors(
        dark = dark,
        success = if (dark) Color(0xFF6CCB5F) else Color(0xFF0F7B0F),
        danger = if (dark) Color(0xFFFF99A4) else Color(0xFFC42B1C),
        warning = if (dark) Color(0xFFFCE100) else Color(0xFF9D5D00),
        neutral = if (dark) Color(0xFFAAAAAA) else Color(0xFF707070),
        accent = scheme.primary,
        textTertiary = scheme.onSurfaceVariant,
        successBg = if (dark) Color(0xFF393D1B) else Color(0xFFDFF6DD),
        dangerBg = if (dark) Color(0xFF442726) else Color(0xFFFDE7E9),
        warningBg = if (dark) Color(0xFF433519) else Color(0xFFFFF4CE),
        infoBg = scheme.surfaceContainerHighest,
    )
    CompositionLocalProvider(LocalStatusColors provides tones) {
        MaterialTheme(colorScheme = scheme) {
            // 正文默认 14sp（Material 默认 16sp），和桌面端一致，标签、输入框里的字也跟着用这个
            ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
        }
    }
}
