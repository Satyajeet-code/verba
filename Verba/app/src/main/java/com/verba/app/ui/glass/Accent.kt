package com.verba.app.ui.glass

import android.app.WallpaperManager
import android.content.Context
import com.verba.app.data.Prefs
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where the coloured parts of Verba (buttons, glows, the recording dot) get
 * their colour. Black stays the base in every mode.
 *
 *  - AUTO: the hue of the phone's theme colour (Material You, taken from the
 *    wallpaper), made as vivid as Nothing red. If the wallpaper itself has no
 *    real colour (black and white, greys), Verba goes black and white.
 *  - RED: the original black and red.
 *  - MONO: always black and white.
 */
object Accent {
    const val AUTO = "auto"
    const val RED = "red"
    const val MONO = "mono"

    data class Colors(
        val accent: Int,
        /** darker shade for the background glow */
        val deep: Int,
        /** brighter shade for "recording" */
        val bright: Int,
        /** text / icons drawn on [accent] */
        val onAccent: Int,
        val mono: Boolean,
        /** short description for Settings */
        val source: String,
    )

    val NOTHING_RED = Colors(0xFFD71921.toInt(), 0xFFA1121A.toInt(), 0xFFFF2B2B.toInt(), 0xFFFFFFFF.toInt(), false, "Nothing red")
    val BLACK_WHITE = Colors(0xFFF4F4F4.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), true, "Black and white")

    /** A wallpaper colour counts as "real colour" from this OKLCH chroma up (greys, cream, sepia stay below). */
    private const val COLOURFUL = 0.06

    fun current(ctx: Context): Colors = when (Prefs.accentMode(ctx)) {
        RED -> NOTHING_RED
        MONO -> BLACK_WHITE
        else -> auto(ctx)
    }

    /** What AUTO resolves to right now (also shown in Settings). */
    fun auto(ctx: Context): Colors {
        val app = ctx.applicationContext
        val wc = try {
            app.getSystemService(WallpaperManager::class.java)?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        } catch (t: Throwable) {
            null
        }
        if (wc != null) {
            val colours = listOfNotNull(wc.primaryColor, wc.secondaryColor, wc.tertiaryColor).map { it.toArgb() }
            if (colours.isNotEmpty() && colours.none { Oklch.chroma(it) >= COLOURFUL }) {
                return BLACK_WHITE.copy(source = "Black and white, because your wallpaper has no colour")
            }
        }
        val theme = app.getColor(android.R.color.system_accent1_500)
        if (Oklch.chroma(theme) < 0.03) return BLACK_WHITE.copy(source = "Black and white, like your phone's theme")
        return vivid(Oklch.hue(theme), "Your phone's theme colour, made vivid")
    }

    /** [hue] at Nothing red's lightness and strength (as strong as the screen can show). */
    fun vivid(hue: Double, source: String = ""): Colors {
        val red = Oklch.of(NOTHING_RED.accent)
        val deepRed = Oklch.of(NOTHING_RED.deep)
        val brightRed = Oklch.of(NOTHING_RED.bright)
        return Colors(
            accent = Oklch.toArgb(red.l, red.c, hue),
            deep = Oklch.toArgb(deepRed.l, deepRed.c, hue),
            bright = Oklch.toArgb(brightRed.l, brightRed.c, hue),
            onAccent = 0xFFFFFFFF.toInt(),
            mono = false,
            source = source,
        )
    }
}

/** OKLCH colour maths (perceptual lightness, chroma, hue), with sRGB gamut mapping. */
object Oklch {
    data class Lch(val l: Double, val c: Double, val h: Double)

    private fun toLinear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun toSrgb(v: Double): Int {
        val x = v.coerceIn(0.0, 1.0)
        val s = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
        return (s * 255).roundToInt().coerceIn(0, 255)
    }

    fun of(argb: Int): Lch {
        val r = toLinear((argb shr 16) and 255)
        val g = toLinear((argb shr 8) and 255)
        val b = toLinear(argb and 255)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val L = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val A = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val B = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        val h = Math.toDegrees(atan2(B, A))
        return Lch(L, hypot(A, B), if (h < 0) h + 360 else h)
    }

    fun chroma(argb: Int) = of(argb).c
    fun hue(argb: Int) = of(argb).h

    private fun linearRgb(L: Double, C: Double, H: Double): DoubleArray {
        val a = C * cos(Math.toRadians(H))
        val b = C * sin(Math.toRadians(H))
        val l = (L + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (L - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (L - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076146010 * s,
        )
    }

    private fun inGamut(v: DoubleArray) = v.all { it >= -1e-4 && it <= 1 + 1e-4 }

    /** Colour with lightness [L] and hue [H]; chroma is lowered just enough to fit on screen. */
    fun toArgb(L: Double, C: Double, H: Double): Int {
        var lo = 0.0
        var hi = C
        repeat(24) {
            val mid = (lo + hi) / 2
            if (inGamut(linearRgb(L, mid, H))) lo = mid else hi = mid
        }
        val rgb = linearRgb(L, lo, H)
        return (0xFF shl 24) or (toSrgb(rgb[0]) shl 16) or (toSrgb(rgb[1]) shl 8) or toSrgb(rgb[2])
    }

    @Suppress("unused")
    private fun near(a: Double, b: Double) = abs(a - b) < 1e-9
}
