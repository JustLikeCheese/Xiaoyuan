package io.github.justlikecheese.xiaoyuan.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/*
 * 由一个种子色生成整套 Material 3 配色（“主题颜色”里的预设色用，壁纸取色走系统的动态配色）。
 * 做法同 Material 3 的色调体系：同一色相按色调（tone，即 CIE L*）取 0~100 的颜色，各角色用固定色调，
 * 只是色相、彩度用 CIE LCh 近似 HCT，彩度超出 sRGB 时自动收到能显示的最大值。
 */

private class Palette(val hue: Double, val chroma: Double) {
    private val cache = HashMap<Int, Color>()
    fun tone(t: Int): Color = cache.getOrPut(t) { lchToColor(t.toDouble(), chroma, hue) }
}

fun seedColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val (_, c, h) = colorToLch(seed)
    val p = Palette(h, maxOf(c, 48.0))
    val s = Palette(h, 16.0)
    val t = Palette((h + 60) % 360, 24.0)
    val n = Palette(h, 5.0)
    val nv = Palette(h, 8.0)
    return if (!dark) lightColorScheme(
        primary = p.tone(40), onPrimary = p.tone(100), primaryContainer = p.tone(90), onPrimaryContainer = p.tone(10),
        inversePrimary = p.tone(80),
        secondary = s.tone(40), onSecondary = s.tone(100), secondaryContainer = s.tone(90), onSecondaryContainer = s.tone(10),
        tertiary = t.tone(40), onTertiary = t.tone(100), tertiaryContainer = t.tone(90), onTertiaryContainer = t.tone(10),
        background = n.tone(98), onBackground = n.tone(10), surface = n.tone(98), onSurface = n.tone(10),
        surfaceVariant = nv.tone(90), onSurfaceVariant = nv.tone(30), surfaceTint = p.tone(40),
        inverseSurface = n.tone(20), inverseOnSurface = n.tone(95), outline = nv.tone(50), outlineVariant = nv.tone(80),
        surfaceBright = n.tone(98), surfaceDim = n.tone(87), surfaceContainerLowest = n.tone(100),
        surfaceContainerLow = n.tone(96), surfaceContainer = n.tone(94), surfaceContainerHigh = n.tone(92),
        surfaceContainerHighest = n.tone(90),
    ) else darkColorScheme(
        primary = p.tone(80), onPrimary = p.tone(20), primaryContainer = p.tone(30), onPrimaryContainer = p.tone(90),
        inversePrimary = p.tone(40),
        secondary = s.tone(80), onSecondary = s.tone(20), secondaryContainer = s.tone(30), onSecondaryContainer = s.tone(90),
        tertiary = t.tone(80), onTertiary = t.tone(20), tertiaryContainer = t.tone(30), onTertiaryContainer = t.tone(90),
        background = n.tone(6), onBackground = n.tone(90), surface = n.tone(6), onSurface = n.tone(90),
        surfaceVariant = nv.tone(30), onSurfaceVariant = nv.tone(80), surfaceTint = p.tone(80),
        inverseSurface = n.tone(90), inverseOnSurface = n.tone(20), outline = nv.tone(60), outlineVariant = nv.tone(30),
        surfaceBright = n.tone(24), surfaceDim = n.tone(6), surfaceContainerLowest = n.tone(4),
        surfaceContainerLow = n.tone(10), surfaceContainer = n.tone(12), surfaceContainerHigh = n.tone(17),
        surfaceContainerHighest = n.tone(22),
    )
}

// ---------------------------------------------------------------- sRGB ↔ CIE LCh（D65）

private const val XN = 0.95047
private const val YN = 1.0
private const val ZN = 1.08883

private fun toLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
private fun fromLinear(c: Double) = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
private fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
private fun fInv(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)

private fun colorToLch(c: Color): Triple<Double, Double, Double> {
    val r = toLinear(c.red.toDouble())
    val g = toLinear(c.green.toDouble())
    val b = toLinear(c.blue.toDouble())
    val x = 0.41233895 * r + 0.35762064 * g + 0.18051042 * b
    val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    val z = 0.01932141 * r + 0.11916382 * g + 0.95034478 * b
    val fx = f(x / XN)
    val fy = f(y / YN)
    val fz = f(z / ZN)
    val l = 116 * fy - 16
    val a = 500 * (fx - fy)
    val bb = 200 * (fy - fz)
    val hue = (Math.toDegrees(atan2(bb, a)) + 360) % 360
    return Triple(l, hypot(a, bb), hue)
}

/** LCh 转 sRGB；超出 sRGB 时 rgb 为 null。 */
private fun lchToRgb(l: Double, c: Double, h: Double): DoubleArray? {
    val a = c * cos(Math.toRadians(h))
    val b = c * sin(Math.toRadians(h))
    val fy = (l + 16) / 116
    val x = XN * fInv(fy + a / 500)
    val y = YN * fInv(fy)
    val z = ZN * fInv(fy - b / 200)
    val r = 3.24096994 * x - 1.53738318 * y - 0.49861076 * z
    val g = -0.96924364 * x + 1.8759675 * y + 0.04155506 * z
    val bl = 0.05563008 * x - 0.20397696 * y + 1.05697151 * z
    val eps = 1e-4
    if (listOf(r, g, bl).any { it < -eps || it > 1 + eps }) return null
    return doubleArrayOf(fromLinear(r.coerceIn(0.0, 1.0)), fromLinear(g.coerceIn(0.0, 1.0)), fromLinear(bl.coerceIn(0.0, 1.0)))
}

/** 色调 l、色相 h 不变，彩度取不超过 c 且 sRGB 能显示的最大值。 */
private fun lchToColor(l: Double, c: Double, h: Double): Color {
    var rgb = lchToRgb(l, c, h)
    if (rgb == null) {
        var lo = 0.0
        var hi = c
        rgb = lchToRgb(l, 0.0, h)
        repeat(24) {
            val mid = (lo + hi) / 2
            val m = lchToRgb(l, mid, h)
            if (m != null) {
                lo = mid
                rgb = m
            } else {
                hi = mid
            }
        }
    }
    val v = rgb ?: doubleArrayOf(l / 100, l / 100, l / 100)
    return Color(v[0].toFloat(), v[1].toFloat(), v[2].toFloat())
}
