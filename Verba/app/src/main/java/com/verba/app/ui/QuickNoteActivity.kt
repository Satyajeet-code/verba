package com.verba.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.verba.app.data.Backend
import com.verba.app.data.Prefs
import com.verba.app.stt.Dictation
import com.verba.app.ui.glass.*
import com.verba.app.widget.NotesWidgetProvider

/**
 * Opened from the widget's "+ Text" button: a glass card floating over the
 * blurred home screen. Type or speak, then Save.
 */
class QuickNoteActivity : GlassActivity(), Dictation.Listener {
    private lateinit var text: android.widget.EditText
    private lateinit var caption: TextView
    private lateinit var mic: MicButton
    private lateinit var dictation: Dictation
    private lateinit var langPill: LinearLayout
    private var dictated = false
    private var typed = false
    private var saving = false
    private var blurListener: java.util.function.Consumer<Boolean>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blurListener = applyWindowBlur(this)
        val p = palette
        dictation = Dictation(this, this)
        val root = FrameLayout(this)
        root.setOnClickListener { close() }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            glass(p, 28f, GlassDrawable.Style.STRONG)
            padDp(18, 16, 18, 14)
            isClickable = true
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(dotLabel("Quick note", 24f, p.text), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(iconButton(p, Icon.CLOSE, 38, GlassDrawable.Style.BUTTON, iconDp = 18, description = "Close") { close() })
        card.addView(head)

        text = field(p, "Type, or tap the mic and speak…", 17f).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP
            minLines = 4
            maxLines = 10
            setLineSpacing(0f, 1.2f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { if (!inserting) typed = true }
            })
        }
        card.addView(text, card.linLp(MATCH, WRAP, t = 10, b = 8))
        caption = label("", 14f, p.textDim, 400, 3).apply { visibility = android.view.View.GONE }
        card.addView(caption, card.linLp(MATCH, WRAP, b = 8))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        langPill = pill(p, Prefs.languageLabel(Prefs.language(this)), Icon.GLOBE, heightDp = 42) {
            Prefs.setLanguage(this, Prefs.nextLanguage(Prefs.language(this)))
            langPill.setPillText(Prefs.languageLabel(Prefs.language(this)))
        }
        row.addView(langPill, LinearLayout.LayoutParams(dpi(112), WRAP))
        row.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
        mic = MicButton(this, p).apply { setOnTap { dictation.toggle() } }
        row.addView(mic, LinearLayout.LayoutParams(dpi(72), dpi(64)))
        row.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(pill(p, "Save", Icon.CHECK, GlassDrawable.Style.ACCENT, heightDp = 42) { save() }, LinearLayout.LayoutParams(dpi(112), WRAP))
        card.addView(row)

        root.addView(card, card.frameLp(MATCH, WRAP, Gravity.CENTER, 16, 0, 16, 0))
        setContentView(root)    // keyboard behaviour: adjustResize|stateVisible in the manifest
        text.requestFocus()
    }

    private var inserting = false

    private fun save() {
        if (saving) return
        dictation.stop()
        val body = text.text.toString()
        if (body.isBlank()) { finish(); return }
        saving = true
        val src = when { typed && dictated -> "mixed"; dictated -> "dictated"; else -> "typed" }
        Backend.async({ Backend.createText("", body, src) }) { r ->
            r.onSuccess {
                NotesWidgetProvider.refresh(this)
                Toast.makeText(applicationContext, "Note saved", Toast.LENGTH_SHORT).show()
                finish()
            }
            r.onFailure { saving = false; GlassToast.show(this, "Could not save: ${it.message}", bottomDp = 40) }
        }
    }

    private fun close() {
        if (text.text.isBlank()) { dictation.cancel(); finish(); return }
        GlassDialog.confirm(this, "Save this note?", "You typed or spoke something here.", "Save",
            cancelLabel = "Discard", onCancel = { dictation.cancel(); finish() }) { save() }
    }

    override fun onBack() = close()

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing && text.text.isNotBlank()) save()
    }

    override fun onDestroy() {
        dictation.cancel()
        blurListener?.let { windowManager.removeCrossWindowBlurEnabledListener(it) }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        dictation.onPermissionResult(requestCode, grantResults)
    }

    override fun onDictationState(state: Dictation.State, live: Boolean) {
        mic.setState(state)
        caption.visibility = if (state == Dictation.State.IDLE) android.view.View.GONE else android.view.View.VISIBLE
        caption.text = when (state) {
            Dictation.State.LOADING -> "Loading speech model…"
            Dictation.State.LISTENING -> "Listening…"
            Dictation.State.FINISHING -> "Finishing…"
            else -> ""
        }
        caption.setTextColor(if (state == Dictation.State.LISTENING) palette.record else palette.textDim)
    }

    override fun onPartial(text: String) {
        if (dictation.state != Dictation.State.LISTENING && dictation.state != Dictation.State.FINISHING) return
        caption.text = when (text) {
            com.verba.app.stt.PARTIAL_SPEAKING -> "Hearing you…"
            com.verba.app.stt.PARTIAL_WRITING -> "Writing…"
            "" -> "Listening…"
            else -> text
        }
    }

    override fun onLevel(level: Float) = mic.setLevel(level)

    override fun onSegment(rawText: String) {
        val pos = text.selectionStart.coerceIn(0, text.length())
        val before = text.text.substring(0, pos)
        Backend.async({ Dictation.prepare(before, rawText) }) { r ->
            val s = r.getOrNull().orEmpty()
            if (s.isEmpty() || isDestroyed) return@async
            val at = text.selectionStart.coerceIn(0, text.length())
            inserting = true
            text.text.insert(at, s)
            inserting = false
            text.setSelection((at + s.length).coerceAtMost(text.length()))
            dictated = true
        }
    }

    companion object {
        /** Blur whatever is behind this window (Android 12+ with blur enabled), else dim it. */
        fun applyWindowBlur(act: android.app.Activity): java.util.function.Consumer<Boolean> {
            val w = act.window
            val wm = act.windowManager
            fun update(enabled: Boolean) {
                if (enabled) {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    w.attributes = w.attributes.apply { blurBehindRadius = act.dpi(36) }
                    w.setDimAmount(0.45f)
                } else {
                    w.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    w.setDimAmount(0.75f)
                }
            }
            update(wm.isCrossWindowBlurEnabled)
            val listener = java.util.function.Consumer<Boolean> { update(it) }
            wm.addCrossWindowBlurEnabledListener(act.mainExecutor, listener)
            return listener
        }
    }
}
