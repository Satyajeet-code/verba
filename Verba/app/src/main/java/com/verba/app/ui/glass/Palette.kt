package com.verba.app.ui.glass

import android.content.Context
import android.graphics.Color

/**
 * Nothing-inspired colours: black everywhere, with one accent colour that
 * follows the phone's theme (see [Accent]). One dark theme everywhere.
 *
 * Glass is frosted: each panel shows a heavily blurred copy of what is behind
 * it under a dense dark tint and a fine grain, so colour glows through but no
 * shapes or text can be read.
 */
class Palette private constructor(
    val dark: Boolean,
    val bgTop: Int,
    val bgBottom: Int,
    /** Soft glows painted into the backdrop (seen through the frosted glass). */
    val blobs: IntArray,
    val dotGrid: Int,
    val glassFill: Int,
    val glassFillStrong: Int,
    val cardFill: Int,
    val rimTop: Int,
    val rimBottom: Int,
    val sheen: Int,
    val text: Int,
    val textDim: Int,
    val textFaint: Int,
    val accent: Int,
    val accent2: Int,
    val onAccent: Int,
    val danger: Int,
    val record: Int,
    val scrim: Int,
) {
    companion object {
        /** Nothing-style red. */
        const val RED = 0xFFD71921.toInt()

        private var cached: Pair<Accent.Colors, Palette>? = null

        /**
         * The palette for the current accent (see [Accent]). Returns the same
         * instance while the colours are unchanged, so a screen can tell when
         * it needs to be redrawn.
         */
        @Synchronized
        fun of(ctx: Context): Palette {
            val a = Accent.current(ctx)
            cached?.let { (key, p) -> if (key == a) return p }
            return build(a).also { cached = a to it }
        }

        private fun build(a: Accent.Colors) = Palette(
            dark = true,
            bgTop = 0xFF000000.toInt(),
            bgBottom = 0xFF050505.toInt(),
            blobs = if (a.mono) intArrayOf(
                0x2EFFFFFF,           // soft white glow, top right
                0x14FFFFFF,           // fainter, lower left
                0x12FFFFFF,           // haze
            ) else intArrayOf(
                (a.accent and 0x00FFFFFF) or (0xB3 shl 24),   // accent glow, top right
                (a.deep and 0x00FFFFFF) or (0x66 shl 24),     // deeper shade, lower left
                0x1AFFFFFF,                                    // faint white haze
            ),
            dotGrid = 0x1AFFFFFF,
            glassFill = 0xD9101010.toInt(),       // bars: 85 % dark over a live blur
            glassFillStrong = 0xF0161616.toInt(), // dialogs, pop-ups, buttons: almost solid
            cardFill = 0xC7121212.toInt(),        // cards over the blurred backdrop
            rimTop = 0x3DFFFFFF,
            rimBottom = 0x0AFFFFFF,
            sheen = 0x0FFFFFFF,
            text = 0xFFFFFFFF.toInt(),
            textDim = 0xA6FFFFFF.toInt(),
            textFaint = 0x5CFFFFFF,
            accent = a.accent,
            accent2 = a.accent,
            onAccent = a.onAccent,
            danger = a.accent,
            record = a.bright,
            scrim = 0xCC000000.toInt(),
        )
    }

    /** Thin outline that keeps panels defined on black. */
    val hairline: Int get() = 0x1FFFFFFF

    fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((Color.alpha(color) * alpha).toInt().coerceIn(0, 255) shl 24)
}
