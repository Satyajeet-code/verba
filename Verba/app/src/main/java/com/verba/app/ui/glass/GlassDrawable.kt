package com.verba.app.ui.glass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference

/**
 * Frosted glass surface (black-and-red theme).
 *
 * What shows through the glass:
 *  * **Live** (Android 12+, when [liveSource] is set): the real content under the
 *    panel (e.g. note cards scrolling under the top bar) is re-drawn into a
 *    RenderNode, blurred and saturated on the GPU.
 *  * **Static** (everything else): the matching region of the frosted aurora
 *    backdrop, so cards in a list still look like frosted glass. Re-drawn on
 *    every scroll so the colours stay aligned.
 *  * **None**: in pop-up windows the system blurs the home screen behind the
 *    window, so the panel only adds its tint.
 *
 * On top of that: a dense dark tint (so nothing behind is readable), a fine
 * grain that gives the frosted texture, a faint sheen and a hairline rim.
 */
class GlassDrawable(
    host: View,
    private val p: Palette,
    var radius: Float,
    var style: Style = Style.PANEL,
) : Drawable() {

    /** EDGE: full-width frosted strip behind the status bar / top bar that fades out at its inner edge. */
    enum class Style { PANEL, STRONG, CARD, BUTTON, ACCENT, DANGER, RECORD, NONE, EDGE }

    /** EDGE only: length (px) over which the frost fades out at the top / bottom. */
    var fadeTop = 0f
    var fadeBottom = 0f
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }

    var liveSource: View? = null
    var blurRadius: Float = 44f * host.resources.displayMetrics.density
    var pressed = false
        set(v) { field = v; invalidateSelf() }

    private val hostRef = WeakReference(host)
    private var auroraRef: WeakReference<AuroraView>? = null
    private val path = Path()
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val grain = Paint().apply {
        shader = android.graphics.BitmapShader(grainTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val hostLoc = IntArray(2)
    private val otherLoc = IntArray(2)
    private var node: Any? = null          // RenderNode on API 29+, kept as Any for clarity
    private var effect: Any? = null        // RenderEffect on API 31+
    private var effectRadius = -1f
    private val density = host.resources.displayMetrics.density

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        hostRef.get()?.invalidate()
    }

    init {
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnScrollChangedListener(scrollListener)
                auroraRef = null
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
            }
        })
        if (host.isAttachedToWindow) host.viewTreeObserver.addOnScrollChangedListener(scrollListener)
    }

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        rect.set(b)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        rim.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            p.rimTop, p.rimBottom, Shader.TileMode.CLAMP,
        )
        sheen.shader = LinearGradient(
            0f, rect.top, 0f, rect.top + rect.height() * 0.6f,
            p.sheen, p.sheen and 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || style == Style.NONE) return
        if (style == Style.EDGE) { drawEdge(canvas); return }
        canvas.save()
        canvas.clipPath(path)
        when (style) {
            Style.ACCENT, Style.DANGER, Style.RECORD -> {
                // Flat red, Nothing style.
                fill.color = if (style == Style.RECORD) p.record else p.accent
                canvas.drawRect(rect, fill)
                if (pressed) {
                    fill.color = 0x33000000
                    canvas.drawRect(rect, fill)
                }
            }
            else -> {
                if (!drawLive(canvas)) drawStaticBackdrop(canvas)
                fill.color = when (style) {
                    Style.STRONG, Style.BUTTON -> p.glassFillStrong
                    Style.CARD -> p.cardFill
                    else -> p.glassFill
                }
                canvas.drawRect(rect, fill)
                canvas.drawRect(rect, grain)
                if (pressed) {
                    fill.color = 0x1FFFFFFF
                    canvas.drawRect(rect, fill)
                }
            }
        }
        canvas.drawRect(rect, sheen)
        canvas.restore()

        val w = density * 1f
        hair.strokeWidth = w
        hair.color = p.hairline
        val half = w / 2f
        canvas.drawRoundRect(
            rect.left + half, rect.top + half, rect.right - half, rect.bottom - half,
            radius - half, radius - half, hair,
        )
        rim.strokeWidth = w
        canvas.drawRoundRect(
            rect.left + half, rect.top + half, rect.right - half, rect.bottom - half,
            radius - half, radius - half, rim,
        )
    }

    private var maskKey = -1f

    /** Alpha mask: opaque in the middle, eased (smoothstep) to clear over the fade lengths. */
    private fun fadeShader(): Shader {
        val h = rect.height().coerceAtLeast(1f)
        val ft = fadeTop.coerceAtMost(h / 2)
        val fb = fadeBottom.coerceAtMost(h / 2)
        val colors = ArrayList<Int>()
        val stops = ArrayList<Float>()
        fun add(pos: Float, alpha: Float) {
            stops += (pos / h).coerceIn(0f, 1f)
            colors += ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
        }
        val n = 8
        if (ft > 0) for (i in 0..n) { val x = i / n.toFloat(); add(ft * x, x * x * (3 - 2 * x)) }
        else add(0f, 1f)
        if (fb > 0) for (i in 0..n) { val x = i / n.toFloat(); add(h - fb + fb * x, 1 - x * x * (3 - 2 * x)) }
        else add(h, 1f)
        return LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            colors.toIntArray(), stops.toFloatArray(), Shader.TileMode.CLAMP,
        )
    }

    /** Frosted strip with a soft, gradient edge so content blurs in seamlessly. */
    private fun drawEdge(canvas: Canvas) {
        val save = canvas.saveLayer(rect, null)
        canvas.clipRect(rect)
        if (!drawLive(canvas)) drawStaticBackdrop(canvas)
        fill.color = p.glassFill
        canvas.drawRect(rect, fill)
        canvas.drawRect(rect, grain)
        if (fadeTop > 0 || fadeBottom > 0) {
            if (maskKey != rect.height()) { mask.shader = fadeShader(); maskKey = rect.height() }
            canvas.drawRect(rect, mask)
        }
        canvas.restoreToCount(save)
    }

    /** GPU blur of the real content behind the panel. Returns false if not possible. */
    private fun drawLive(canvas: Canvas): Boolean {
        val src = liveSource ?: return false
        val host = hostRef.get() ?: return false
        if (Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated) return false
        if (src.width == 0 || src.height == 0 || !src.isAttachedToWindow) return false
        return try {
            val pad = blurRadius.toInt()
            val w = bounds.width()
            val h = bounds.height()
            host.getLocationInWindow(hostLoc)
            src.getLocationInWindow(otherLoc)
            val n = (node as? RenderNode) ?: RenderNode("glass").also { node = it }
            n.setPosition(0, 0, w + 2 * pad, h + 2 * pad)
            val rc = n.beginRecording()
            try {
                rc.translate(
                    (otherLoc[0] - hostLoc[0] - bounds.left + pad).toFloat(),
                    (otherLoc[1] - hostLoc[1] - bounds.top + pad).toFloat(),
                )
                src.draw(rc)
            } finally {
                n.endRecording()
            }
            if (effect == null || effectRadius != blurRadius) {
                val sat = ColorMatrix().apply { setSaturation(1.3f) }
                effect = RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(sat)),
                    RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP),
                )
                effectRadius = blurRadius
            }
            n.setRenderEffect(effect as RenderEffect)
            canvas.save()
            canvas.translate((bounds.left - pad).toFloat(), (bounds.top - pad).toFloat())
            canvas.drawRenderNode(n)
            canvas.restore()
            true
        } catch (t: Throwable) {
            liveSource = null           // never try again on a device that cannot do it
            false
        }
    }

    private fun drawStaticBackdrop(canvas: Canvas) {
        val host = hostRef.get() ?: return
        val aurora = auroraRef?.get() ?: (host.rootView.findViewWithTag<View>(AuroraView.TAG) as? AuroraView)
            ?.also { auroraRef = WeakReference(it) } ?: return
        val bmp = aurora.frosted ?: return
        if (aurora.width == 0 || aurora.height == 0) return
        host.getLocationInWindow(hostLoc)
        aurora.getLocationInWindow(otherLoc)
        canvas.save()
        canvas.translate((otherLoc[0] - hostLoc[0]).toFloat(), (otherLoc[1] - hostLoc[1]).toFloat())
        canvas.scale(aurora.width / bmp.width.toFloat(), aurora.height / bmp.height.toFloat())
        canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        canvas.restore()
    }

    companion object {
        private var tile: android.graphics.Bitmap? = null

        /** Fine random grain (frosted texture), shared by every glass panel. */
        private fun grainTile(): android.graphics.Bitmap {
            tile?.let { return it }
            val n = 96
            val rnd = java.util.Random(7)
            val px = IntArray(n * n) {
                val a = (kotlin.math.abs(rnd.nextGaussian()) * 9).toInt().coerceAtMost(28)
                (a shl 24) or 0x00FFFFFF
            }
            return android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888)
                .also { tile = it }
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
