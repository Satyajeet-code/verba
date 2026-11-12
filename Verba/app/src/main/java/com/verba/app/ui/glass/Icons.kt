package com.verba.app.ui.glass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

enum class Icon { SLIDERS, PLUS, MIC, TRASH, BACK, CLOSE, RESTORE, PLAY, PAUSE, STOP, SEARCH, DOWNLOAD, CHECK, NOTE, WAVE, CHIP, GLOBE, COPY, TEXT }

/** Line icons drawn in a 24×24 box, so no image resources are needed in the app UI. */
class IconDrawable(
    private val icon: Icon,
    color: Int,
    private val sizePx: Int,
    strokeDp: Float = 2f,
    density: Float = 1f,
) : Drawable() {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
        strokeWidth = strokeDp * density
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private val path = Path()
    private val r = RectF()

    var color: Int
        get() = stroke.color
        set(v) { stroke.color = v; solid.color = v; invalidateSelf() }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f)
        canvas.scale(s, s)
        val sw = stroke.strokeWidth
        stroke.strokeWidth = sw / s
        path.reset()
        when (icon) {
            Icon.SLIDERS -> {
                line(4f, 6f, 7f, 6f); line(11f, 6f, 20f, 6f); path.addCircle(9f, 6f, 2f, Path.Direction.CW)
                line(4f, 12f, 13f, 12f); line(17f, 12f, 20f, 12f); path.addCircle(15f, 12f, 2f, Path.Direction.CW)
                line(4f, 18f, 5f, 18f); line(9f, 18f, 20f, 18f); path.addCircle(7f, 18f, 2f, Path.Direction.CW)
            }
            Icon.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Icon.MIC -> {
                r.set(9f, 3f, 15f, 14f); path.addRoundRect(r, 3f, 3f, Path.Direction.CW)
                r.set(5.5f, 5f, 18.5f, 17.5f); path.addArc(r, 0f, 180f)
                line(12f, 17.5f, 12f, 21f); line(9f, 21f, 15f, 21f)
            }
            Icon.TRASH -> {
                line(4f, 7f, 20f, 7f)
                path.moveTo(9f, 7f); path.lineTo(9f, 4.5f); path.lineTo(15f, 4.5f); path.lineTo(15f, 7f)
                path.moveTo(6f, 7f); path.lineTo(7f, 20f); path.lineTo(17f, 20f); path.lineTo(18f, 7f)
                line(10f, 11f, 10f, 16f); line(14f, 11f, 14f, 16f)
            }
            Icon.BACK -> { path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f) }
            Icon.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Icon.RESTORE -> {
                path.moveTo(4f, 9f); path.lineTo(14f, 9f)
                path.cubicTo(17.3f, 9f, 20f, 11.7f, 20f, 15f)
                path.cubicTo(20f, 18.3f, 17.3f, 21f, 14f, 21f); path.lineTo(8f, 21f)
                path.moveTo(8f, 5f); path.lineTo(4f, 9f); path.lineTo(8f, 13f)
            }
            Icon.PLAY -> {
                path.moveTo(8f, 5f); path.lineTo(19f, 12f); path.lineTo(8f, 19f); path.close()
                canvas.drawPath(path, solid); path.reset()
            }
            Icon.PAUSE -> {
                r.set(6.5f, 5f, 10f, 19f); canvas.drawRoundRect(r, 1.2f, 1.2f, solid)
                r.set(14f, 5f, 17.5f, 19f); canvas.drawRoundRect(r, 1.2f, 1.2f, solid)
            }
            Icon.STOP -> { r.set(6.5f, 6.5f, 17.5f, 17.5f); canvas.drawRoundRect(r, 2.5f, 2.5f, solid) }
            Icon.SEARCH -> { path.addCircle(10.5f, 10.5f, 6f, Path.Direction.CW); line(15f, 15f, 20f, 20f) }
            Icon.DOWNLOAD -> {
                line(12f, 4f, 12f, 15f)
                path.moveTo(7f, 10.5f); path.lineTo(12f, 15.5f); path.lineTo(17f, 10.5f)
                line(5f, 19.5f, 19f, 19.5f)
            }
            Icon.CHECK -> { path.moveTo(5f, 12.5f); path.lineTo(10f, 17.5f); path.lineTo(19f, 7f) }
            Icon.NOTE -> {
                r.set(5f, 3f, 19f, 21f); path.addRoundRect(r, 2.5f, 2.5f, Path.Direction.CW)
                line(8.5f, 8f, 15.5f, 8f); line(8.5f, 12f, 15.5f, 12f); line(8.5f, 16f, 12.5f, 16f)
            }
            Icon.TEXT -> { line(5f, 6f, 19f, 6f); line(12f, 6f, 12f, 19f) }
            Icon.WAVE -> {
                line(4f, 10f, 4f, 14f); line(8f, 6.5f, 8f, 17.5f); line(12f, 3.5f, 12f, 20.5f)
                line(16f, 7.5f, 16f, 16.5f); line(20f, 10.5f, 20f, 13.5f)
            }
            Icon.CHIP -> {
                r.set(7f, 7f, 17f, 17f); path.addRoundRect(r, 2f, 2f, Path.Direction.CW)
                for (x in floatArrayOf(10f, 14f)) { line(x, 3.5f, x, 7f); line(x, 17f, x, 20.5f) }
                for (y in floatArrayOf(10f, 14f)) { line(3.5f, y, 7f, y); line(17f, y, 20.5f, y) }
            }
            Icon.GLOBE -> {
                path.addCircle(12f, 12f, 8.5f, Path.Direction.CW)
                line(3.5f, 12f, 20.5f, 12f)
                r.set(8f, 3.5f, 16f, 20.5f); path.addOval(r, Path.Direction.CW)
            }
            Icon.COPY -> {
                r.set(8f, 8f, 19f, 19f); path.addRoundRect(r, 2.5f, 2.5f, Path.Direction.CW)
                path.moveTo(5f, 15f); path.lineTo(5f, 7f)
                path.quadTo(5f, 5f, 7f, 5f); path.lineTo(15f, 5f)
            }
        }
        canvas.drawPath(path, stroke)
        stroke.strokeWidth = sw
        canvas.restore()
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
        path.moveTo(x1, y1); path.lineTo(x2, y2)
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; solid.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { stroke.colorFilter = colorFilter; solid.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
