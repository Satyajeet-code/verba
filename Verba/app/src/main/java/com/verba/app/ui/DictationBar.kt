package com.verba.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.verba.app.data.Prefs
import com.verba.app.stt.Dictation
import com.verba.app.ui.glass.*

/** Red mic button; while listening, a ring of dots lights up with your voice (glyph style). */
class MicButton(context: Context, private val p: Palette) : FrameLayout(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private var spin = 0
    private var level = 0f
    private var active = false
    private val face = FrameLayout(context)
    private val icon = IconDrawable(Icon.MIC, p.onAccent, context.dpi(26), 2.2f, context.resources.displayMetrics.density)
    private val iconView = android.widget.ImageView(context).apply {
        setImageDrawable(icon); scaleType = android.widget.ImageView.ScaleType.CENTER
    }
    private val glassFace: GlassDrawable

    init {
        setWillNotDraw(false)
        clipChildren = false
        glassFace = face.glass(p, 27f, GlassDrawable.Style.ACCENT)
        face.addView(iconView, LayoutParams(MATCH, MATCH))
        addView(face, LayoutParams(context.dpi(54), context.dpi(54), Gravity.CENTER))
        face.pressable()
        isClickable = false
    }

    fun setOnTap(l: (View) -> Unit) { face.isClickable = true; face.setOnClickListener(l) }

    fun setState(state: Dictation.State) {
        active = state == Dictation.State.LISTENING
        glassFace.style = when (state) {
            Dictation.State.LISTENING -> GlassDrawable.Style.RECORD
            Dictation.State.IDLE -> GlassDrawable.Style.ACCENT
            else -> GlassDrawable.Style.STRONG
        }
        iconView.setImageDrawable(
            IconDrawable(
                if (active) Icon.STOP else Icon.MIC,
                if (state == Dictation.State.IDLE || active) p.onAccent else p.textDim,
                context.dpi(26), 2.2f, resources.displayMetrics.density,
            )
        )
        face.invalidate()
        if (!active) setLevel(0f)
    }

    fun setLevel(v: Float) {
        val target = if (active) v else 0f
        ValueAnimator.ofFloat(level, target).apply {
            duration = 90
            interpolator = DecelerateInterpolator()
            addUpdateListener { level = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = context.dp(33)
        val n = 28
        val lit = (level * n).toInt().coerceIn(1, n)
        spin = (spin + 1) % n
        for (i in 0 until n) {
            val a = Math.toRadians((i * 360.0 / n) - 90)
            val on = ((i - spin + n) % n) < lit
            ring.color = if (on) p.record else 0x2EFFFFFF
            canvas.drawCircle(
                cx + (radius * Math.cos(a)).toFloat(),
                cy + (radius * Math.sin(a)).toFloat(),
                context.dp(if (on) 1.9f else 1.4f), ring,
            )
        }
    }
}

/**
 * Bottom glass capsule used while writing: language chip · mic · extra action,
 * plus a floating caption bubble showing words as they are recognised.
 */
class DictationBar(
    context: Context,
    private val p: Palette,
    live: View?,
    trailing: View?,
    private val onMic: () -> Unit,
) : LinearLayout(context) {

    val mic = MicButton(context, p)
    val caption: TextView
    val captionCard: LinearLayout
    private val status: TextView
    private val langPill: LinearLayout
    val bar: LinearLayout

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false

        captionCard = LinearLayout(context).apply {
            orientation = VERTICAL
            glass(p, 22f, GlassDrawable.Style.STRONG, live = live)
            padDp(16, 10, 16, 12)
            visibility = View.GONE
        }
        status = context.monoLabel("Listening", 11f, p.record, bold = true)
        caption = context.label("", 16f, p.text, 500, 4)
        captionCard.addView(status)
        captionCard.addView(caption)
        addView(captionCard, linLp(MATCH, WRAP, l = 4, r = 4, b = 10))

        bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            glass(p, 40f, GlassDrawable.Style.PANEL, live = live)
            padDp(8, 4, 8, 4)
            clipChildren = false
        }
        langPill = context.pill(p, Prefs.languageLabel(Prefs.language(context)), Icon.GLOBE, heightDp = 44) {
            val next = Prefs.nextLanguage(Prefs.language(context))
            Prefs.setLanguage(context, next)
            refreshLanguage()
        }
        bar.addView(langPill, LayoutParams(context.dpi(118), WRAP))
        bar.addView(View(context), LayoutParams(0, 1, 1f))
        mic.setOnTap { onMic() }
        bar.addView(mic, LayoutParams(context.dpi(78), context.dpi(72)))
        bar.addView(View(context), LayoutParams(0, 1, 1f))
        if (trailing != null) bar.addView(trailing)
        else bar.addView(View(context), LayoutParams(context.dpi(118), 1))
        addView(bar, LayoutParams(MATCH, WRAP))
    }

    fun refreshLanguage() = langPill.setPillText(Prefs.languageLabel(Prefs.language(context)))

    fun onState(state: Dictation.State, live: Boolean) {
        mic.setState(state)
        when (state) {
            Dictation.State.IDLE -> captionCard.visibility = View.GONE
            Dictation.State.LOADING -> showStatus("Loading speech model…", p.textDim)
            Dictation.State.LISTENING -> showStatus(
                if (live) "Listening · ${Prefs.languageLabel(Prefs.language(context))}"
                else "Listening · text appears after each pause", p.record,
            )
            Dictation.State.FINISHING -> showStatus("Finishing…", p.textDim)
        }
    }

    private fun showStatus(text: String, color: Int) {
        status.setMonoText("● $text")
        status.setTextColor(color)
        if (captionCard.visibility != View.VISIBLE) {
            caption.text = ""
            caption.visibility = View.GONE
            captionCard.visibility = View.VISIBLE
            captionCard.alpha = 0f
            captionCard.animate().alpha(1f).setDuration(160).start()
        }
    }

    fun onPartial(text: String) {
        val t = when (text) {
            com.verba.app.stt.PARTIAL_SPEAKING -> "Hearing you…"
            com.verba.app.stt.PARTIAL_WRITING -> "Writing…"
            else -> text
        }
        caption.text = t
        caption.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
        captionCard.invalidate()
    }
}
