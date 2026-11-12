package com.verba.app.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.verba.app.audio.MicRecorder
import com.verba.app.data.Backend
import com.verba.app.data.Format
import com.verba.app.data.Note
import com.verba.app.data.Prefs
import com.verba.app.stt.AutoTranscriber
import com.verba.app.stt.Dictation
import com.verba.app.stt.NoModelException
import com.verba.app.stt.NoSpeechException
import com.verba.app.stt.SttManager
import com.verba.app.ui.glass.*
import com.verba.app.widget.NotesWidgetProvider
import java.io.File
import java.util.UUID

const val EXTRA_AUTOSTART = "autostart"

/** Nothing-style glyph: three rings of dots that light up red with your voice. */
class OrbView(context: Context, private val p: Palette) : View(context) {
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level = 0f
    private var phase = 0
    var recording = false
        set(v) {
            field = v
            removeCallbacks(spin)
            if (v) post(spin) else { level = 0f }
            invalidate()
        }

    private val spin = object : Runnable {
        override fun run() {
            phase++
            invalidate()
            if (recording) postDelayed(this, 60)
        }
    }

    fun setLevel(v: Float) {
        ValueAnimator.ofFloat(level, v).apply {
            duration = 100
            addUpdateListener { level = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(spin)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val half = minOf(width, height) / 2f
        val r = context.dp(2.1f)
        for ((ring, spec) in RINGS.withIndex()) {
            val n = spec.second
            val radius = half * spec.first - r
            val lit = if (recording) ((0.08f + 0.92f * level) * n).toInt() else 0
            val offset = (phase * (ring + 1)) % n
            for (i in 0 until n) {
                val a = Math.toRadians(i * 360.0 / n - 90)
                val on = ((i - offset + n) % n) < lit
                dot.color = if (on) p.record else 0x24FFFFFF
                c.drawCircle(cx + (radius * Math.cos(a)).toFloat(), cy + (radius * Math.sin(a)).toFloat(),
                    if (on) r * 1.15f else r * 0.85f, dot)
            }
        }
    }

    companion object {
        private val RINGS = listOf(1.0f to 64, 0.82f to 52, 0.64f to 40)
    }
}

/** mm:ss in dot-matrix digits with a drawn colon (the font's own colon is a dot cluster). */
class DotClock(context: Context, p: Palette, sizeSp: Float) : LinearLayout(context) {
    private val min = context.dotLabel("00", sizeSp, p.text)
    private val sec = context.dotLabel("00", sizeSp, p.text)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val colon = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent }
            override fun onDraw(c: Canvas) {
                val rad = width / 4.5f
                c.drawCircle(width / 2f, height * 0.36f, rad, paint)
                c.drawCircle(width / 2f, height * 0.70f, rad, paint)
            }
        }
        addView(min)
        addView(colon, LayoutParams(context.dpi(sizeSp * 0.36f), LayoutParams.MATCH_PARENT).apply {
            leftMargin = context.dpi(sizeSp * 0.12f); rightMargin = context.dpi(sizeSp * 0.12f)
        })
        addView(sec)
    }

    fun set(ms: Long) {
        val s = (ms / 1000).coerceAtLeast(0)
        min.text = "%02d".format(s / 60)
        sec.text = "%02d".format(s % 60)
    }
}

/**
 * Voice notes. Without a note id it records a new one; with an id it plays it
 * back and can turn it into text with the on-device speech model.
 */
open class VoiceActivity : GlassActivity() {
    protected open val quick = false
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MicRecorder? = null
    private var recordFile: File? = null
    private var player: MediaPlayer? = null
    private var note: Note? = null
    private var blurListener: java.util.function.Consumer<Boolean>? = null

    // recorder views
    private var orb: OrbView? = null
    private var timer: DotClock? = null
    // player views
    private var playBtn: FrameLayout? = null
    private var seek: SeekBar? = null
    private var pos: TextView? = null
    private var transcript: TextView? = null
    private var transcribeBtn: LinearLayout? = null
    private var progress: GlassProgress? = null
    private var titleField: android.widget.EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (quick) blurListener = QuickNoteActivity.applyWindowBlur(this)
        AutoTranscriber.addListener(transcriptions)
        val id = intent.getStringExtra(EXTRA_NOTE_ID)
        if (id == null) showRecorder() else loadPlayer(id)
    }

    // ===================================================================== recording
    private var status: TextView? = null
    private var saveBtn: FrameLayout? = null
    private var saveCaption: TextView? = null
    private var pauseBtn: FrameLayout? = null
    private var pauseCaption: TextView? = null

    /** Recording starts as soon as the screen opens. Buttons: Discard · Save · Pause/Resume. */
    private fun showRecorder() {
        val p = palette
        val root: FrameLayout
        val holder: FrameLayout
        if (quick) {
            root = FrameLayout(this)
            holder = FrameLayout(this).apply { glass(p, 32f, GlassDrawable.Style.STRONG); padDp(0, 0, 0, 26) }
            root.addView(holder, holder.frameLp(MATCH, WRAP, Gravity.CENTER, 20, 0, 20, 0))
            setContentView(root)
        } else {
            val scene = buildScene()
            root = scene.root
            holder = scene.content
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(
            monoLabel(if (quick) "Voice note" else "New voice note", 12f, p.textDim, bold = true),
            col.linLp(WRAP, WRAP, t = if (quick) 26 else 0, b = 12),
        )
        orb = OrbView(this, p)
        val orbSize = dpi(if (quick) 210 else 290)
        val orbBox = FrameLayout(this)
        orbBox.addView(orb, FrameLayout.LayoutParams(orbSize, orbSize))
        timer = DotClock(this, p, if (quick) 40f else 54f)
        orbBox.addView(timer, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        col.addView(orbBox, col.linLp(orbSize, orbSize))
        status = monoLabel("Starting…", 11f, p.textDim, bold = true).apply { gravity = Gravity.CENTER }
        col.addView(status, col.linLp(MATCH, WRAP, t = 18, b = 26))

        // three equal columns, centred: Discard · Save · Pause
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun column(button: FrameLayout, caption: TextView): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(FrameLayout(this@VoiceActivity).apply {
                addView(button, FrameLayout.LayoutParams(button.layoutParams.width, button.layoutParams.height, Gravity.CENTER))
            }, LinearLayout.LayoutParams(dpi(84), dpi(84)))
            addView(caption, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dpi(6) })
        }
        val discardBtn = iconButton(p, Icon.CLOSE, 60, GlassDrawable.Style.BUTTON, description = "Discard") { discard() }
        saveBtn = iconButton(p, Icon.CHECK, 80, GlassDrawable.Style.ACCENT, iconDp = 32, description = "Save") {
            if (recorder == null) { if (startRecording()) renderState() } else stopAndSave()
        }
        pauseBtn = iconButton(p, Icon.PAUSE, 60, GlassDrawable.Style.BUTTON, description = "Pause") { togglePause() }
        saveCaption = monoLabel("Save", 10.5f, p.text, bold = true)
        pauseCaption = monoLabel("Pause", 10.5f, p.textDim, bold = true)
        row.addView(column(discardBtn, monoLabel("Discard", 10.5f, p.textDim, bold = true)), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(column(saveBtn!!, saveCaption!!), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(column(pauseBtn!!, pauseCaption!!), LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(row, col.linLp(MATCH, WRAP, l = 12, r = 12))

        holder.addView(col, col.frameLp(MATCH, WRAP, Gravity.CENTER))
        if (!quick) {
            val close = iconButton(p, Icon.BACK, 44, GlassDrawable.Style.BUTTON, description = "Back") { onBack() }
            root.addView(close, close.frameLp(dpi(44), dpi(44), Gravity.TOP or Gravity.START, 16, 8, 0, 0))
            edgeToEdge(root) { l, t, _, b ->
                (close.layoutParams as FrameLayout.LayoutParams).apply { topMargin = t + dpi(8); leftMargin = l + dpi(16) }
                close.requestLayout()
                holder.setPadding(0, t, 0, b)
            }
        } else {
            root.setOnClickListener { if (recorder == null) finish() }
            holder.isClickable = true
        }
        renderState()
        col.post { if (recorder == null && startRecording()) renderState() }
    }

    /** Update status text, timer dots and the Save / Pause buttons. */
    private fun renderState() {
        val p = palette
        val r = recorder
        val paused = r?.paused == true
        orb?.recording = r != null && !paused
        status?.apply {
            when {
                r == null -> { setMonoText("Tap Record to start"); setTextColor(p.textDim) }
                paused -> { setMonoText("Paused"); setTextColor(p.textDim) }
                else -> { setMonoText("● Recording"); setTextColor(p.record) }
            }
        }
        saveCaption?.setMonoText(if (r == null) "Record" else "Save")
        setIcon(saveBtn, if (r == null) Icon.MIC else Icon.CHECK, p.onAccent, 32)
        pauseBtn?.alpha = if (r == null) 0.35f else 1f
        pauseBtn?.isEnabled = r != null
        pauseCaption?.setMonoText(if (paused) "Resume" else "Pause")
        setIcon(pauseBtn, if (paused) Icon.PLAY else Icon.PAUSE, p.text, 24)
    }

    private fun setIcon(button: FrameLayout?, icon: Icon, color: Int, sizeDp: Int) {
        (button?.getChildAt(0) as? android.widget.ImageView)?.setImageDrawable(
            IconDrawable(icon, color, dpi(sizeDp), 1.8f, resources.displayMetrics.density)
        )
    }

    private fun togglePause() {
        val r = recorder ?: return
        if (r.paused) {
            if (!r.resume()) GlassToast.show(this, "Could not resume the microphone", bottomDp = 40)
        } else {
            r.pause()
        }
        renderState()
    }

    private fun startRecording(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), Dictation.REQ_MIC)
            return false
        }
        val dirPath = File(filesDir, "audio").apply { mkdirs() }
        val f = File(dirPath, UUID.randomUUID().toString().replace("-", "") + ".wav")
        val r = MicRecorder(wavFile = f, source = MediaRecorder.AudioSource.MIC, onLevel = { orb?.setLevel(it) })
        if (!r.start()) {
            f.delete()
            GlassToast.show(this, "The microphone is busy or unavailable", bottomDp = 40)
            return false
        }
        recorder = r
        recordFile = f
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.post(tick)
        return true
    }

    private val tick = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            timer?.set(r.elapsedMs)
            handler.postDelayed(this, 200)
        }
    }

    private fun stopRecorder(): Long {
        val r = recorder ?: return 0
        recorder = null
        handler.removeCallbacks(tick)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        orb?.recording = false
        return r.stop()
    }

    private fun stopAndSave() {
        val f = recordFile ?: return
        val ms = stopRecorder()
        recordFile = null
        if (ms < 600) {
            f.delete()
            renderState()
            GlassToast.show(this, "Too short — tap Record to try again", bottomDp = 40)
            return
        }
        val auto = Prefs.autoTranscribe(this)
        Backend.async({ Backend.createVoice(f.absolutePath, ms) }) { r ->
            r.onFailure { GlassToast.show(this, "Could not save: ${it.message}", bottomDp = 40) }
            r.onSuccess { n ->
                NotesWidgetProvider.refresh(this)
                if (quick) {
                    if (auto) AutoTranscriber.run(this, n.id, f.absolutePath)
                    Toast.makeText(
                        applicationContext,
                        "Voice note saved (${Format.duration(ms)})" + if (auto) " · transcribing" else "",
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                } else {
                    intent.putExtra(EXTRA_NOTE_ID, n.id)
                    loadPlayer(n.id, autoTranscribe = auto)
                }
            }
        }
    }

    private fun discard() {
        if (recorder == null) { finish(); return }
        GlassDialog.confirm(this, "Discard recording", "This recording will not be saved.", "Discard", destructive = true) {
            stopRecorder()
            recordFile?.delete()
            recordFile = null
            finish()
        }
    }

    override fun onBack() {
        if (recorder != null) discard() else finish()
    }

    override fun onStop() {
        super.onStop()
        // Never lose audio: leaving the screen while recording saves what was captured.
        if (recorder != null && !isChangingConfigurations) stopAndSave()
        player?.takeIf { it.isPlaying }?.pause()
        updatePlayIcon()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Dictation.REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                if (recorder == null && startRecording()) renderState()
            } else {
                GlassToast.show(this, "Microphone permission is needed to record", bottomDp = 40)
            }
        }
    }

    // ===================================================================== playback
    private fun loadPlayer(id: String, autoTranscribe: Boolean = false) {
        Backend.async({ Backend.getNote(id) }) { r ->
            r.onFailure { GlassToast.show(this, "Note not found"); finish() }
            r.onSuccess {
                showPlayer(it)
                if (autoTranscribe && it.body.isBlank()) transcribe(auto = true)
            }
        }
    }

    private fun showPlayer(n: Note) {
        note = n
        val p = palette
        val scene = buildScene()
        val scroll = ScrollView(this).apply { clipToPadding = false; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; padDp(14, 0, 14, 0) }
        scroll.addView(col)
        scene.content.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- card 1: title + player
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            glass(p, 26f, GlassDrawable.Style.CARD)
            padDp(22, 20, 22, 22)
        }
        titleField = field(p, "Voice note", 24f, 750).apply {
            setText(n.title)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        card.addView(titleField)
        card.addView(monoLabel("${Format.stamp(n.createdAt)}  ·  ${Format.duration(n.durationMs)}", 10.5f, p.textDim), card.linLp(WRAP, WRAP, t = 6, b = 18))
        val prow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playBtn = iconButton(p, Icon.PLAY, 60, GlassDrawable.Style.ACCENT, iconDp = 26, description = "Play") { togglePlay() }
        prow.addView(playBtn)
        val pcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        seek = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(p.textFaint)
            max = n.durationMs.toInt().coerceAtLeast(1)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) { ensurePlayer()?.seekTo(v); pos?.text = Format.duration(v.toLong()) }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        pcol.addView(seek)
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; padDp(16, 0, 16, 0) }
        pos = monoLabel("0:00", 10.5f, p.textDim)
        times.addView(pos, LinearLayout.LayoutParams(0, WRAP, 1f))
        times.addView(monoLabel(Format.duration(n.durationMs), 10.5f, p.textDim))
        pcol.addView(times)
        prow.addView(pcol, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dpi(6) })
        card.addView(prow)
        col.addView(card)

        // ---- card 2: transcript
        val tcard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            glass(p, 26f, GlassDrawable.Style.CARD)
            padDp(22, 18, 22, 20)
        }
        tcard.addView(monoLabel("Transcript", 11f, p.textDim, bold = true))
        transcript = label("", 16f, p.text, 400).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.25f)
            setPadding(0, dpi(8), 0, dpi(14))
        }
        tcard.addView(transcript)
        progress = GlassProgress(this, p).apply { visibility = View.GONE }
        tcard.addView(progress, tcard.linLp(MATCH, dpi(6), b = 14))
        val trow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        transcribeBtn = pill(p, "Transcribe", Icon.TEXT, GlassDrawable.Style.ACCENT) { transcribe() }
        trow.addView(transcribeBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        trow.addView(View(this), LinearLayout.LayoutParams(dpi(10), 1))
        trow.addView(pill(p, "Text note", Icon.COPY) { copyToTextNote() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        tcard.addView(trow)
        col.addView(tcard, col.linLp(MATCH, WRAP, t = 12))
        showTranscript(n.body)
        if (AutoTranscriber.isRunning(n.id)) showWorking(AutoTranscriber.progress(n.id) ?: 0f)

        // ---- top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            glass(p, 26f, GlassDrawable.Style.PANEL, live = scene.content)
            padDp(4)
        }
        top.addView(iconButton(p, Icon.BACK, 44, GlassDrawable.Style.NONE, description = "Back") { onBack() })
        top.addView(monoLabel("Voice note", 11.5f, p.textDim, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(iconButton(p, Icon.TRASH, 44, GlassDrawable.Style.NONE, tint = p.danger, description = "Delete") { deleteNote() })
        scene.root.addView(top, top.frameLp(MATCH, WRAP, Gravity.TOP, 14, 8, 14, 0))

        val topEdge = frostedEdge(scene, top = true)
        val bottomEdge = frostedEdge(scene, top = false)
        edgeToEdge(scene.root) { l, t, r, b ->
            topEdge.setEdgeHeight(t + dpi(72))
            bottomEdge.setEdgeHeight(b + dpi(20))
            (top.layoutParams as FrameLayout.LayoutParams).apply { topMargin = t + dpi(8); leftMargin = l + dpi(14); rightMargin = r + dpi(14) }
            top.requestLayout()
            scroll.setPadding(l, t + dpi(72), r, b + dpi(24))
        }
    }

    private fun showTranscript(text: String) {
        val t = transcript ?: return
        if (text.isBlank()) {
            t.text = "Not transcribed yet. Tap Transcribe to turn this recording into text on your phone."
            t.setTextColor(palette.textFaint)
            transcribeBtn?.setPillText("Transcribe")
        } else {
            t.text = text
            t.setTextColor(palette.text)
            transcribeBtn?.setPillText("Redo")
        }
    }

    private fun ensurePlayer(): MediaPlayer? {
        player?.let { return it }
        val path = note?.audioPath ?: return null
        return try {
            MediaPlayer().apply {
                setDataSource(path)
                prepare()
                setOnCompletionListener { seekTo(0); updatePlayIcon(); seek?.progress = 0; pos?.text = "0:00" }
            }.also { player = it }
        } catch (e: Exception) {
            GlassToast.show(this, "Cannot play this recording: ${e.message}", bottomDp = 40)
            null
        }
    }

    private fun togglePlay() {
        val mp = ensurePlayer() ?: return
        if (mp.isPlaying) mp.pause() else { mp.start(); handler.post(playTick) }
        updatePlayIcon()
    }

    private val playTick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            seek?.progress = mp.currentPosition
            pos?.text = Format.duration(mp.currentPosition.toLong())
            if (mp.isPlaying) handler.postDelayed(this, 200)
        }
    }

    private fun updatePlayIcon() {
        val b = playBtn ?: return
        val playing = player?.isPlaying == true
        (b.getChildAt(0) as android.widget.ImageView).setImageDrawable(
            com.verba.app.ui.glass.IconDrawable(if (playing) Icon.PAUSE else Icon.PLAY, palette.onAccent, dpi(26), 2f, resources.displayMetrics.density))
    }

    /** Started from this screen (not by the widget): errors are worth a message. */
    private var requestedHere = false

    private fun transcribe(auto: Boolean = false) {
        val n = note ?: return
        val path = n.audioPath ?: return
        if (AutoTranscriber.isRunning(n.id)) return
        requestedHere = !auto
        // Runs in the background service: leaving this screen does not stop it.
        AutoTranscriber.run(this, n.id, path, replace = !auto && n.body.isNotBlank())
        showWorking(0f)
    }

    private fun showWorking(fraction: Float) {
        val bar = progress ?: return
        bar.visibility = View.VISIBLE
        bar.indeterminate = fraction <= 0f
        bar.fraction = fraction
        transcribeBtn?.isEnabled = false
        transcribeBtn?.setPillText("Working…")
        transcript?.takeIf { note?.body.isNullOrBlank() }?.let {
            it.text = "Turning the recording into text… You can leave this screen; the text will be saved to the note."
            it.setTextColor(palette.textFaint)
        }
    }

    private fun showIdle() {
        progress?.visibility = View.GONE
        progress?.indeterminate = false
        transcribeBtn?.isEnabled = true
        showTranscript(note?.body.orEmpty())
    }

    private val transcriptions = object : AutoTranscriber.Listener {
        override fun onProgress(noteId: String, fraction: Float) {
            if (noteId == note?.id && !isDestroyed) showWorking(fraction)
        }

        override fun onFinished(noteId: String, note: Note?, error: Throwable?) {
            if (noteId != this@VoiceActivity.note?.id || isDestroyed) return
            if (note != null) this@VoiceActivity.note = note
            showIdle()
            val here = requestedHere
            requestedHere = false
            when (error) {
                null -> {}
                is NoModelException -> if (here) Dictation.offerDownload(this@VoiceActivity)
                    else GlassToast.show(this@VoiceActivity, "Download a speech model in Settings to auto-transcribe", bottomDp = 40)
                is NoSpeechException -> GlassToast.show(this@VoiceActivity, "No speech was found in this recording", bottomDp = 40)
                else -> GlassToast.show(this@VoiceActivity, "Transcription failed: ${error.message}", bottomDp = 40)
            }
        }
    }

    /** Pick up text saved while this screen was in the background. */
    private fun refreshNote() {
        val n = note ?: return
        if (AutoTranscriber.isRunning(n.id)) { showWorking(AutoTranscriber.progress(n.id) ?: 0f); return }
        Backend.async({ Backend.getNote(n.id) }) { r ->
            r.onSuccess { fresh ->
                if (isDestroyed || fresh.id != note?.id) return@onSuccess
                note = fresh
                if (!AutoTranscriber.isRunning(fresh.id)) showIdle()
            }
        }
    }

    private fun copyToTextNote() {
        val n = note ?: return
        if (n.body.isBlank()) {
            GlassToast.show(this, "Transcribe the recording first", bottomDp = 40); return
        }
        val title = titleField?.text?.toString().orEmpty()
        Backend.async({ Backend.createText(title, n.body, "dictated") }) { r ->
            r.onSuccess {
                NotesWidgetProvider.refresh(this)
                startActivity(Intent(this, EditorActivity::class.java).putExtra(EXTRA_NOTE_ID, it.id))
            }
        }
    }

    private fun saveTitle() {
        val n = note ?: return
        val t = titleField?.text?.toString() ?: return
        if (t == n.title) return
        val ctx = applicationContext
        Backend.async({ Backend.updateNote(n.id, t, Backend.getNote(n.id).body) }) { r ->
            r.onSuccess { note = it; NotesWidgetProvider.refresh(ctx) }
        }
    }

    private fun deleteNote() {
        val n = note ?: return
        player?.release(); player = null
        Backend.async({ Backend.trash(n.id) }) { r ->
            r.onSuccess {
                Toast.makeText(applicationContext, "Moved to Recently Deleted", Toast.LENGTH_SHORT).show()
                NotesWidgetProvider.refresh(this)
                finish()
            }
            r.onFailure { GlassToast.show(this, "Could not delete: ${it.message}", bottomDp = 40) }
        }
    }

    override fun onPause() {
        super.onPause()
        saveTitle()
    }

    override fun onResume() {
        super.onResume()
        if (note != null) refreshNote()
        AutoTranscriber.resumePending(this)
    }

    override fun onDestroy() {
        AutoTranscriber.removeListener(transcriptions)
        handler.removeCallbacksAndMessages(null)
        if (recorder != null) stopRecorder()
        player?.release()
        player = null
        blurListener?.let { windowManager.removeCrossWindowBlurEnabledListener(it) }
        super.onDestroy()
    }
}

/** Same recorder, as a floating glass card over the home screen (from the widget). */
class QuickVoiceActivity : VoiceActivity() {
    override val quick = true
}
