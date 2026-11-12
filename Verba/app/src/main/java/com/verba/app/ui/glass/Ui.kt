package com.verba.app.ui.glass

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

// ------------------------------------------------------------------ units & layout params
fun Context.dp(v: Number): Float = v.toFloat() * resources.displayMetrics.density
fun Context.dpi(v: Number): Int = dp(v).roundToInt()

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun View.frameLp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
    FrameLayout.LayoutParams(w, h, gravity).apply { setMargins(context.dpi(l), context.dpi(t), context.dpi(r), context.dpi(b)) }

fun View.linLp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(context.dpi(l), context.dpi(t), context.dpi(r), context.dpi(b)) }

fun View.padDp(l: Int, t: Int = l, r: Int = l, b: Int = t) =
    setPadding(context.dpi(l), context.dpi(t), context.dpi(r), context.dpi(b))

/**
 * Nothing-style type: a dot-matrix face (Doto) for headings and numbers, a
 * mono face (Space Mono) for small uppercase labels and buttons, and a clean
 * grotesk (Space Grotesk) for everything you read and write, including notes.
 * Hindi falls back to the phone's Devanagari font automatically.
 */
object Fonts {
    var dot: Typeface = Typeface.MONOSPACE; private set
    var mono: Typeface = Typeface.MONOSPACE; private set
    var monoBold: Typeface = Typeface.MONOSPACE; private set
    var body: Typeface = Typeface.DEFAULT; private set
    var bodyMedium: Typeface = Typeface.DEFAULT; private set
    var bodyBold: Typeface = Typeface.DEFAULT_BOLD; private set

    fun init(c: Context) {
        val r = c.resources
        runCatching {
            dot = r.getFont(com.verba.app.R.font.doto_bold)
            mono = r.getFont(com.verba.app.R.font.space_mono_regular)
            monoBold = r.getFont(com.verba.app.R.font.space_mono_bold)
            body = r.getFont(com.verba.app.R.font.space_grotesk_regular)
            bodyMedium = r.getFont(com.verba.app.R.font.space_grotesk_medium)
            bodyBold = r.getFont(com.verba.app.R.font.space_grotesk_bold)
        }
    }

    fun weight(w: Int): Typeface = when {
        w >= 650 -> bodyBold
        w >= 500 -> bodyMedium
        else -> body
    }
}

// ------------------------------------------------------------------ factories
/** Dot-matrix heading or number (avoid punctuation: Doto draws it as dot clusters). */
fun Context.dotLabel(text: CharSequence, sizeSp: Float, color: Int): TextView = TextView(this).apply {
    this.text = text.toString().uppercase()
    textSize = sizeSp
    setTextColor(color)
    typeface = Fonts.dot
    includeFontPadding = false
    letterSpacing = 0.04f
}

/** Small uppercase mono label, e.g. "12 NOTES · 3 VOICE". */
fun Context.monoLabel(text: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false, lines: Int = 1): TextView =
    TextView(this).apply {
        this.text = text.toString().uppercase()
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Fonts.monoBold else Fonts.mono
        letterSpacing = 0.12f
        if (lines > 0) {
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }
    }

fun TextView.setMonoText(text: CharSequence) { this.text = text.toString().uppercase() }

/** Single-line label: if it would be cut off with "…", step the font size down (never below [minSp]). */
fun TextView.shrinkToFit(minSp: Float) {
    val startPx = textSize
    val minPx = minSp * resources.displayMetrics.scaledDensity
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            if (textSize != startPx) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, startPx)
        }
    })
    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        val tv = v as TextView
        val l = tv.layout ?: return@addOnLayoutChangeListener
        if (l.lineCount > 0 && l.getEllipsisCount(0) > 0 && tv.textSize - 1f >= minPx) {
            tv.post { tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tv.textSize - 1f) }
        }
    }
}

/** Readable text (note content, explanations). */
fun Context.label(
    text: CharSequence,
    sizeSp: Float,
    color: Int,
    weight: Int = 400,
    lines: Int = 0,
): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(color)
    typeface = Fonts.weight(weight)
    includeFontPadding = true
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
}

fun Context.field(p: Palette, hint: String, sizeSp: Float, weight: Int = 400): EditText = EditText(this).apply {
    this.hint = hint
    textSize = sizeSp
    typeface = Fonts.weight(weight)
    setTextColor(p.text)
    setHintTextColor(p.textFaint)
    highlightColor = p.withAlpha(p.accent, 0.35f)
    background = null
    setPadding(0, 0, 0, 0)
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(p.accent); setSize(dpi(2), dpi(20))
        }
        runCatching {
            textSelectHandle?.mutate()?.let { it.setTint(p.accent); setTextSelectHandle(it) }
            textSelectHandleLeft?.mutate()?.let { it.setTint(p.accent); setTextSelectHandleLeft(it) }
            textSelectHandleRight?.mutate()?.let { it.setTint(p.accent); setTextSelectHandleRight(it) }
        }
    }
}

class RoundOutline(private val radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}

/** Gives a view a glass background and rounded clipping. Returns the drawable for tweaks. */
fun View.glass(
    p: Palette,
    radiusDp: Float,
    style: GlassDrawable.Style = GlassDrawable.Style.PANEL,
    live: View? = null,
): GlassDrawable {
    val r = context.dp(radiusDp)
    val d = GlassDrawable(this, p, r, style)
    d.liveSource = live
    background = d
    outlineProvider = RoundOutline(r)
    clipToOutline = true
    return d
}

/** Apple-style press feedback: shrink slightly and brighten while touched. */
fun View.pressable(): View {
    setOnTouchListener { v, e ->
        val g = v.background as? GlassDrawable
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start(); g?.pressed = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                    .setInterpolator(DecelerateInterpolator()).start(); g?.pressed = false
            }
        }
        false
    }
    return this
}

fun Context.iconView(icon: Icon, color: Int, sizeDp: Int = 22): ImageView = ImageView(this).apply {
    setImageDrawable(IconDrawable(icon, color, dpi(sizeDp), 1.7f, resources.displayMetrics.density))
    scaleType = ImageView.ScaleType.CENTER
}

/** Round glass button with an icon. */
fun Context.iconButton(
    p: Palette,
    icon: Icon,
    sizeDp: Int = 44,
    style: GlassDrawable.Style = GlassDrawable.Style.BUTTON,
    tint: Int = if (style == GlassDrawable.Style.ACCENT || style == GlassDrawable.Style.RECORD) p.onAccent else p.text,
    iconDp: Int = 22,
    description: String? = null,
    onClick: (View) -> Unit,
): FrameLayout = FrameLayout(this).apply {
    glass(p, sizeDp / 2f, style)
    addView(iconView(icon, tint, iconDp), FrameLayout.LayoutParams(MATCH, MATCH))
    layoutParams = ViewGroup.LayoutParams(dpi(sizeDp), dpi(sizeDp))
    contentDescription = description
    isClickable = true
    setOnClickListener(onClick)
    pressable()
}

/** Capsule button with optional icon and label. */
fun Context.pill(
    p: Palette,
    text: String,
    icon: Icon? = null,
    style: GlassDrawable.Style = GlassDrawable.Style.BUTTON,
    heightDp: Int = 44,
    onClick: (View) -> Unit,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    glass(p, heightDp / 2f, style)
    val fg = when (style) {
        GlassDrawable.Style.ACCENT, GlassDrawable.Style.DANGER, GlassDrawable.Style.RECORD -> p.onAccent
        else -> p.text
    }
    setPadding(dpi(if (icon != null) 14 else 18), 0, dpi(18), 0)
    if (icon != null) addView(iconView(icon, fg, 18), linLp(dpi(20), dpi(20), r = 8))
    addView(monoLabel(text, 12.5f, fg, bold = true).apply { shrinkToFit(9.5f) })
    minimumHeight = dpi(heightDp)
    isClickable = true
    setOnClickListener(onClick)
    pressable()
}

fun LinearLayout.setPillText(text: String) {
    for (i in 0 until childCount) (getChildAt(i) as? TextView)?.text = text.uppercase()
}

/** Segmented control (e.g. All · Text · Voice) on a glass capsule. */
class Segmented(
    context: Context,
    private val p: Palette,
    labels: List<String>,
    selected: Int,
    private val onSelect: (Int) -> Unit,
) : LinearLayout(context) {
    private val items = mutableListOf<TextView>()
    var selected = selected
        private set

    init {
        orientation = HORIZONTAL
        glass(p, 20f, GlassDrawable.Style.PANEL)
        val pad = context.dpi(4)
        setPadding(pad, pad, pad, pad)
        labels.forEachIndexed { i, s ->
            val t = context.monoLabel(s, 12f, p.text, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(context.dpi(14), 0, context.dpi(14), 0)
                isClickable = true
                setOnClickListener { select(i, true) }
            }
            items += t
            addView(t, LayoutParams(0, context.dpi(32), 1f))
        }
        select(selected, false)
    }

    fun select(i: Int, notify: Boolean) {
        selected = i
        items.forEachIndexed { j, t ->
            if (j == i) {
                t.glass(p, 16f, GlassDrawable.Style.ACCENT)
                t.setTextColor(p.onAccent)
            } else {
                t.background = null
                t.setTextColor(p.textDim)
            }
        }
        if (notify) onSelect(i)
    }
}

/** On/off switch: red track when on, outlined when off. */
class DotSwitch(context: Context, private val p: Palette, checked: Boolean, private val onChange: (Boolean) -> Unit) : View(context) {
    var checked = checked
        private set
    private var pos = if (checked) 1f else 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isClickable = true
        contentDescription = "Switch"
        setOnClickListener { set(!this.checked, true) }
    }

    fun set(v: Boolean, notify: Boolean) {
        checked = v
        ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
            duration = 140
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
        }.start()
        if (notify) onChange(v)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dpi(52), context.dpi(30))

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val rad = h / 2
        r.set(0f, 0f, width.toFloat(), h)
        paint.style = Paint.Style.FILL
        paint.color = if (checked) p.accent else 0x14FFFFFF
        c.drawRoundRect(r, rad, rad, paint)
        if (!checked) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(1)
            paint.color = 0x40FFFFFF
            c.drawRoundRect(r, rad, rad, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = if (checked) p.onAccent else 0x99FFFFFF.toInt()
        val knob = rad - context.dp(4)
        val cx = rad + (width - 2 * rad) * pos
        c.drawCircle(cx, rad, knob, paint)
    }
}

/** Thin glass progress bar. */
class GlassProgress(context: Context, private val p: Palette) : View(context) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var indeterminate = false
        set(v) { field = v; if (v) anim.start() else anim.cancel(); invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val rad = h / 2
        track.color = if (p.dark) 0x26FFFFFF else 0x1A0F172A
        r.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(r, rad, rad, track)
        bar.shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), 0f, p.accent, p.accent2, android.graphics.Shader.TileMode.CLAMP)
        if (indeterminate) {
            val w = width * 0.3f
            val x = (width + w) * phase - w
            r.set(maxOf(0f, x), 0f, minOf(width.toFloat(), x + w), h)
        } else {
            r.set(0f, 0f, width * fraction, h)
        }
        if (r.width() > 0) c.drawRoundRect(r, rad, rad, bar)
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); anim.cancel() }
}

// ------------------------------------------------------------------ base activity
/** Root of every screen: aurora backdrop + content layer that glass bars can blur. */
class Scene(val root: FrameLayout, val content: FrameLayout, val aurora: AuroraView?)

abstract class GlassActivity : Activity() {
    lateinit var palette: Palette
        private set
    private val backHandlers = ArrayDeque<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = Palette.of(this)
        // Android 13+: the back gesture arrives here (onBackPressed is not called on Android 16).
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { handleBack() }
        }
    }

    override fun onResume() {
        super.onResume()
        // The accent colour changed (new wallpaper or theme, or the Colour setting): redraw this screen
        // and the home-screen widget.
        com.verba.app.widget.NotesWidgetProvider.refreshIfStale(this)
        if (Palette.of(this) !== palette) recreate()
    }

    private fun handleBack() {
        val h = backHandlers.removeLastOrNull()
        if (h != null) h() else onBack()
    }

    fun buildScene(withAurora: Boolean = true): Scene {
        val root = FrameLayout(this)
        val content = FrameLayout(this)
        var aurora: AuroraView? = null
        if (withAurora) {
            aurora = AuroraView(this, palette)
            content.addView(aurora, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        return Scene(root, content, aurora)
    }

    /** Draw behind the system bars; [onInsets] receives bar + keyboard insets in px. */
    fun edgeToEdge(root: View, onInsets: (left: Int, top: Int, right: Int, bottom: Int) -> Unit) {
        window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        window.isNavigationBarContrastEnforced = false
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (palette.dark) 0 else light, light)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            onInsets(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        root.requestApplyInsets()
    }

    /**
     * Full-width frosted strip under the status bar + top bar ([top] = true) or
     * above the navigation bar. Content scrolling underneath is blurred, and the
     * strip fades out at its inner edge so there is no hard line. Size it with
     * [setEdgeHeight] from the insets callback. Touches pass through it.
     */
    fun frostedEdge(scene: Scene, top: Boolean): View {
        val v = View(this)
        val d = GlassDrawable(v, palette, 0f, GlassDrawable.Style.EDGE)
        d.liveSource = scene.content
        if (top) d.fadeBottom = dp(28) else d.fadeTop = dp(28)
        v.background = d
        v.isClickable = false
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // index 1: above the content, below the floating bars
        scene.root.addView(v, 1, FrameLayout.LayoutParams(MATCH, 0, if (top) Gravity.TOP else Gravity.BOTTOM))
        return v
    }

    fun View.setEdgeHeight(px: Int) {
        val lp = layoutParams as FrameLayout.LayoutParams
        if (lp.height != px) { lp.height = px; requestLayout() }
    }

    fun pushBack(handler: () -> Unit) { backHandlers.addLast(handler) }
    fun popBack(handler: () -> Unit) { backHandlers.remove(handler) }

    /** Android 12: the back gesture arrives here. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    /** Called when no dialog wants the back gesture. */
    open fun onBack() {
        if (isTaskRoot) moveTaskToBack(true) else finish()
    }
}

// ------------------------------------------------------------------ toast & dialog
object GlassToast {
    private const val TAG = "verba.toast"

    fun show(
        activity: GlassActivity,
        message: String,
        action: String? = null,
        bottomDp: Int = 112,
        onAction: (() -> Unit)? = null,
    ) {
        val p = activity.palette
        val decor = activity.window.decorView as? FrameLayout ?: return
        decor.findViewWithTag<View>(TAG)?.let { decor.removeView(it) }
        val pill = LinearLayout(activity).apply {
            tag = TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            glass(p, 22f, GlassDrawable.Style.STRONG)
            setPadding(activity.dpi(18), activity.dpi(12), activity.dpi(18), activity.dpi(12))
            addView(activity.label(message, 14f, p.text, 500, 2))
        }
        if (action != null && onAction != null) {
            val a = activity.monoLabel(action, 13f, p.accent, bold = true).apply {
                setPadding(activity.dpi(16), 0, 0, 0)
                setOnClickListener { onAction(); hide(pill) }
            }
            pill.addView(a)
        }
        decor.addView(pill, pill.frameLp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 24, 0, 24, bottomDp))
        pill.alpha = 0f
        pill.translationY = activity.dp(16)
        pill.animate().alpha(1f).translationY(0f).setDuration(220).start()
        pill.postDelayed({ hide(pill) }, if (action != null) 4500 else 2600)
    }

    private fun hide(v: View) {
        val parent = v.parent as? ViewGroup ?: return
        v.animate().alpha(0f).translationY(v.context.dp(12)).setDuration(180)
            .withEndAction { parent.removeView(v) }.start()
    }
}

object GlassDialog {
    fun confirm(
        activity: GlassActivity,
        title: String,
        message: String,
        confirmLabel: String,
        destructive: Boolean = false,
        cancelLabel: String = "Cancel",
        onCancel: (() -> Unit)? = null,
        plainTitle: Boolean = false,
        onConfirm: () -> Unit,
    ) {
        val p = activity.palette
        val decor = activity.window.decorView as? FrameLayout ?: return
        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(p.scrim)
            isClickable = true
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            glass(p, 28f, GlassDrawable.Style.STRONG)
            padDp(22, 22, 22, 18)
            addView(if (plainTitle) activity.label(title, 19f, p.text, 700, 2) else activity.dotLabel(title, 22f, p.text))
            addView(activity.label(message, 15f, p.textDim).apply { setPadding(0, activity.dpi(12), 0, activity.dpi(20)) })
        }
        lateinit var dismiss: () -> Unit
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(activity.pill(p, cancelLabel) { dismiss(); onCancel?.invoke() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(View(activity), LinearLayout.LayoutParams(activity.dpi(10), 1))
        row.addView(
            activity.pill(p, confirmLabel, style = if (destructive) GlassDrawable.Style.DANGER else GlassDrawable.Style.ACCENT) {
                dismiss(); onConfirm()
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        card.addView(row)
        scrim.addView(card, card.frameLp(MATCH, WRAP, Gravity.CENTER, 28, 0, 28, 0))
        dismiss = {
            activity.popBack(dismiss)
            scrim.animate().alpha(0f).setDuration(150).withEndAction { decor.removeView(scrim) }.start()
        }
        scrim.setOnClickListener { dismiss(); onCancel?.invoke() }
        card.isClickable = true
        decor.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        activity.pushBack(dismiss)
        scrim.alpha = 0f
        card.scaleX = 1.08f; card.scaleY = 1.08f
        scrim.animate().alpha(1f).setDuration(180).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }
}
