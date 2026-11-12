package com.verba.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.verba.app.data.Backend
import com.verba.app.data.Format
import com.verba.app.stt.Dictation
import com.verba.app.ui.glass.*
import com.verba.app.widget.NotesWidgetProvider
import java.util.concurrent.atomic.AtomicReference

/** Text note: type, or tap the mic and speak Hindi / English. Saves as you go. */
class EditorActivity : GlassActivity(), Dictation.Listener {
    private lateinit var title: android.widget.EditText
    private lateinit var body: android.widget.EditText
    private lateinit var status: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var dictBar: DictationBar
    private lateinit var dictation: Dictation
    private val handler = Handler(Looper.getMainLooper())
    private val noteId = AtomicReference<String?>(null)
    private var suppressWatcher = false
    private var typed = false
    private var dictated = false
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = palette
        noteId.set(savedInstanceState?.getString(EXTRA_NOTE_ID) ?: intent.getStringExtra(EXTRA_NOTE_ID))
        val scene = buildScene()
        dictation = Dictation(this, this)

        // ---- the note itself: one big glass sheet
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            glass(p, 26f, GlassDrawable.Style.CARD)
            padDp(22, 20, 22, 28)
        }
        title = field(p, "Title", 26f, 750).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
        }
        body = field(p, "Start typing, or tap the mic and speak in Hindi or English…", 17f).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP
            minLines = 8
            setLineSpacing(0f, 1.25f)
        }
        sheet.addView(title)
        sheet.addView(body, sheet.linLp(MATCH, WRAP, t = 14))
        val sheetWrap = FrameLayout(this)
        sheetWrap.addView(sheet, sheet.frameLp(MATCH, WRAP, Gravity.NO_GRAVITY, 14, 0, 14, 0))
        scroll.addView(sheetWrap)
        scene.content.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet.setOnClickListener { focusBodyEnd() }

        // ---- top bar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            glass(p, 26f, GlassDrawable.Style.PANEL, live = scene.content)
            padDp(4)
        }
        topBar.addView(iconButton(p, Icon.BACK, 44, GlassDrawable.Style.NONE, description = "Back") { finishAndSave() })
        status = monoLabel("", 11f, p.textDim).apply { gravity = Gravity.CENTER }
        topBar.addView(status, LinearLayout.LayoutParams(0, WRAP, 1f))
        topBar.addView(iconButton(p, Icon.TRASH, 44, GlassDrawable.Style.NONE, tint = p.danger, description = "Delete") { delete() })
        scene.root.addView(topBar, topBar.frameLp(MATCH, WRAP, Gravity.TOP, 14, 8, 14, 0))

        // ---- bottom: language · mic · hide keyboard
        val done = iconButton(p, Icon.CHECK, 44, GlassDrawable.Style.BUTTON, description = "Done") {
            hideKeyboard(); save(force = true)
        }
        val doneWrap = FrameLayout(this).apply {
            addView(done, FrameLayout.LayoutParams(dpi(44), dpi(44), Gravity.END or Gravity.CENTER_VERTICAL))
            layoutParams = LinearLayout.LayoutParams(dpi(118), dpi(44))
        }
        dictBar = DictationBar(this, p, scene.content, doneWrap) { dictation.toggle() }
        scene.root.addView(dictBar, dictBar.frameLp(MATCH, WRAP, Gravity.BOTTOM, 14, 0, 14, 0))

        val topEdge = frostedEdge(scene, top = true)
        edgeToEdge(scene.root) { l, t, r, b ->
            topEdge.setEdgeHeight(t + dpi(72))
            (topBar.layoutParams as FrameLayout.LayoutParams).apply { topMargin = t + dpi(8); leftMargin = l + dpi(14); rightMargin = r + dpi(14) }
            (dictBar.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = b + dpi(12); leftMargin = l + dpi(14); rightMargin = r + dpi(14) }
            topBar.requestLayout(); dictBar.requestLayout()
            // The scroll area ends above the mic bar (and keyboard) so the cursor never hides behind them.
            (scroll.layoutParams as FrameLayout.LayoutParams).bottomMargin = b + dpi(92)
            scroll.requestLayout()
            scroll.setPadding(l, t + dpi(72), r, dpi(16))
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                typed = true
                scheduleSave()
            }
        }
        title.addTextChangedListener(watcher)
        body.addTextChangedListener(watcher)

        val id = noteId.get()
        if (id != null) {
            status.setMonoText("")
            Backend.async({ Backend.getNote(id) }) { r ->
                r.onSuccess { n ->
                    suppressWatcher = true
                    title.setText(n.title)
                    body.setText(n.body)
                    suppressWatcher = false
                    status.setMonoText("Edited ${Format.stamp(n.updatedAt)}")
                    if (n.deletedAt != null) status.setMonoText("In Recently Deleted")
                }
                r.onFailure { GlassToast.show(this, "Note not found"); finish() }
            }
        } else {
            status.setMonoText("New note")
            body.requestFocus()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_NOTE_ID, noteId.get())
    }

    // ------------------------------------------------------------------ saving
    private val saveRunnable = Runnable { save() }

    private fun scheduleSave() {
        dirty = true
        status.setMonoText("Editing…")
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 700)
    }

    private fun source() = when {
        typed && dictated -> "mixed"
        dictated -> "dictated"
        else -> "typed"
    }

    private fun save(force: Boolean = false) {
        handler.removeCallbacks(saveRunnable)
        if (!dirty && !force) return
        dirty = false
        val t = title.text.toString()
        val b = body.text.toString()
        val src = source()
        // Runs on the single io thread, so a create is always finished before the next update.
        Backend.async({
            val id = noteId.get()
            when {
                id != null -> Backend.updateNote(id, t, b, src)
                t.isBlank() && b.isBlank() -> null
                else -> Backend.createText(t, b, src).also { noteId.set(it.id) }
            }
        }) { r ->
            r.onSuccess { if (it != null && !isDestroyed) status.setMonoText("Saved") }
            r.onFailure { if (!isDestroyed) GlassToast.show(this, "Could not save: ${it.message}") }
            NotesWidgetProvider.refresh(this)
        }
    }

    private fun finishAndSave() {
        dictation.cancel()
        save()
        val ctx = applicationContext
        Backend.io.execute {
            noteId.get()?.let { runCatching { Backend.discardIfEmpty(it) } }
            NotesWidgetProvider.refresh(ctx)
        }
        finish()
    }

    override fun onBack() = finishAndSave()

    override fun onPause() {
        super.onPause()
        save()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) dictation.stop()
    }

    override fun onDestroy() {
        dictation.cancel()
        super.onDestroy()
    }

    private fun delete() {
        dictation.cancel()
        handler.removeCallbacks(saveRunnable)
        val id = noteId.get()
        if (id == null) { finish(); return }
        save(force = true)
        Backend.async({ Backend.trash(id) }) { r ->
            r.onSuccess {
                Toast.makeText(applicationContext, "Moved to Recently Deleted", Toast.LENGTH_SHORT).show()
                NotesWidgetProvider.refresh(this)
                finish()
            }
            r.onFailure { GlassToast.show(this, "Could not delete: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ dictation
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        dictation.onPermissionResult(requestCode, grantResults)
    }

    override fun onDictationState(state: Dictation.State, live: Boolean) {
        dictBar.onState(state, live)
        if (state == Dictation.State.LISTENING && !title.hasFocus() && !body.hasFocus()) {
            focusBodyEnd(keepKeyboard = false)
        }
    }

    override fun onPartial(text: String) = dictBar.onPartial(text)

    override fun onLevel(level: Float) = dictBar.mic.setLevel(level)

    override fun onSegment(rawText: String) {
        val target = if (title.hasFocus()) title else body
        val pos = target.selectionStart.coerceAtLeast(0).coerceAtMost(target.length())
        val before = target.text.substring(0, pos)
        Backend.async({ Dictation.prepare(before, rawText) }) { r ->
            val s = r.getOrNull().orEmpty()
            if (s.isEmpty() || isDestroyed) return@async
            val at = target.selectionStart.coerceAtLeast(0).coerceAtMost(target.length())
            suppressWatcher = true
            target.text.insert(at, s)
            suppressWatcher = false
            target.setSelection((at + s.length).coerceAtMost(target.length()))
            dictated = true
            scheduleSave()
        }
    }

    private fun focusBodyEnd(keepKeyboard: Boolean = true) {
        body.requestFocus()
        body.setSelection(body.length())
        if (keepKeyboard) {
            getSystemService(InputMethodManager::class.java).showSoftInput(body, 0)
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(body.windowToken, 0)
        currentFocus?.clearFocus()
    }
}
