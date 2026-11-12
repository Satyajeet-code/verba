package com.verba.app.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.view.View
import kotlin.math.max

/**
 * The backdrop behind every screen: pure black, a fine Nothing-style dot grid
 * and two soft red glows.
 *
 * The glows are painted once into a small bitmap and scaled up (naturally
 * soft and cheap). A much smaller copy ([frosted]) is what static glass panels
 * show through their tint, so they glow red where the backdrop does.
 */
class AuroraView(context: Context, private val palette: Palette) : View(context) {

    var soft: Bitmap? = null
        private set
    var frosted: Bitmap? = null
        private set

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint()
    private val dst = Rect()

    init {
        tag = TAG
        setWillNotDraw(false)
        dotPaint.shader = BitmapShader(dotTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /** One grid cell with a single round dot, tiled across the screen. */
    private fun dotTile(): Bitmap {
        val d = resources.displayMetrics.density
        val cell = max(8, (16 * d).toInt())
        val bmp = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.dotGrid }
        Canvas(bmp).drawCircle(cell / 2f, cell / 2f, 1.1f * d, p)
        return bmp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) render(w, h)
    }

    private fun render(w: Int, h: Int) {
        val bw = max(8, w / SCALE)
        val bh = max(8, h / SCALE)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val base = Paint()
        base.shader = LinearGradient(0f, 0f, 0f, bh.toFloat(), palette.bgTop, palette.bgBottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), base)

        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        val longSide = max(bw, bh).toFloat()
        for ((i, b) in GLOWS.withIndex()) {
            val color = palette.blobs[i % palette.blobs.size]
            val cx = b[0] * bw
            val cy = b[1] * bh
            val r = b[2] * longSide
            glow.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(color, palette.withAlpha(color, 0.35f), color and 0x00FFFFFF),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            c.drawCircle(cx, cy, r, glow)
        }
        // No recycle(): the render thread may still be drawing the old bitmaps.
        soft = bmp
        frosted = Bitmap.createScaledBitmap(bmp, max(2, bw / 6), max(2, bh / 6), true)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = soft ?: return
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dotPaint)
    }

    companion object {
        const val TAG = "verba.aurora"
        private const val SCALE = 6

        /** x, y (fractions of the screen) and radius (fraction of the long side). */
        private val GLOWS = arrayOf(
            floatArrayOf(1.02f, 0.06f, 0.55f),
            floatArrayOf(-0.05f, 0.78f, 0.45f),
            floatArrayOf(0.55f, 0.45f, 0.30f),
        )
    }
}
