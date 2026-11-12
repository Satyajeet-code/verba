package com.verba.app.stt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.verba.app.audio.MicRecorder
import com.verba.app.data.Backend
import com.verba.app.data.Prefs
import com.verba.app.ui.SettingsActivity
import com.verba.app.ui.glass.*

/**
 * Speak-to-type controller shared by the note editor and the widget's quick
 * note. Microphone → model session → cleaned sentences delivered to [Listener].
 */
class Dictation(private val act: GlassActivity, private val listener: Listener) {

    enum class State { IDLE, LOADING, LISTENING, FINISHING }

    interface Listener {
        fun onDictationState(state: State, live: Boolean)
        /** In-progress words (live model) or a PARTIAL_* marker from a pause-based model. */
        fun onPartial(text: String)
        /** Text to insert: the caller passes the text before the cursor to [prepare]. */
        fun onSegment(rawText: String)
        fun onLevel(level: Float)
    }

    var state = State.IDLE
        private set
    private var live = true
    private var recorder: MicRecorder? = null
    private var session: SttSession? = null
    private val main = Handler(Looper.getMainLooper())

    fun toggle() {
        when (state) {
            State.IDLE -> start()
            State.LISTENING -> stop()
            else -> {}
        }
    }

    fun start() {
        if (state != State.IDLE) return
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        set(State.LOADING)
        SttManager.acquire(act) { r ->
            if (act.isFinishing || act.isDestroyed) return@acquire
            r.onFailure { e ->
                set(State.IDLE)
                when (e) {
                    is NoModelException -> offerDownload(act)
                    is NoGeminiKeyException -> GlassDialog.confirm(
                        act, "Gemini key needed",
                        "Gemini is selected for speech, but no API key is saved. Add your key, or pick an on-device model.",
                        "Open Settings",
                    ) { act.startActivity(Intent(act, SettingsActivity::class.java)) }
                    else -> GlassToast.show(act, "Could not load the speech model: ${e.message}")
                }
            }
            r.onSuccess { begin(it) }
        }
    }

    /** Forward from Activity.onRequestPermissionsResult. */
    fun onPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else GlassToast.show(act, "Microphone permission is needed to speak notes")
    }

    private fun begin(engine: SttEngine) {
        live = engine.live
        val s = engine.newSession(Prefs.language(act), object : SessionCallback {
            override fun onPartial(text: String) { main.post { listener.onPartial(text) } }
            override fun onSegment(text: String) { main.post { listener.onSegment(text) } }
            override fun onFinished() { main.post { finished() } }
            override fun onError(e: Throwable) {
                main.post {
                    finished()
                    GlassToast.show(act, "Speech recognition stopped: ${e.message}")
                }
            }
        })
        val r = MicRecorder(onChunk = { s.accept(it) }, onLevel = { listener.onLevel(it) })
        if (!r.start()) {
            s.cancel()
            set(State.IDLE)
            GlassToast.show(act, "The microphone is busy or unavailable")
            return
        }
        session = s
        recorder = r
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        set(State.LISTENING)
    }

    fun stop() {
        if (state != State.LISTENING) return
        recorder?.stop()
        recorder = null
        set(State.FINISHING)
        session?.finish()
    }

    /** Stop immediately, dropping audio that was not yet transcribed (activity closing). */
    fun cancel() {
        recorder?.stop()
        recorder = null
        session?.cancel()
        session = null
        if (state != State.IDLE) finished()
    }

    private fun finished() {
        session = null
        act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        listener.onPartial("")
        set(State.IDLE)
    }

    private fun set(s: State) {
        state = s
        listener.onDictationState(s, live)
    }

    companion object {
        const val REQ_MIC = 41

        fun offerDownload(act: GlassActivity) {
            GlassDialog.confirm(
                act,
                "Download a speech model",
                "Download a speech model once to turn speech into text on your phone " +
                    "(Srota Hinglish is best for Hindi and English mixed), or add your own Gemini key to use Google's cloud.",
                "Choose model",
            ) { act.startActivity(Intent(act, SettingsActivity::class.java)) }
        }

        /**
         * Clean a raw sentence and fit it after [before] (the text left of the
         * cursor). Runs Python, so call it off the main thread.
         */
        fun prepare(before: String, raw: String): String =
            Backend.joinText(before, Backend.cleanSegment(raw, true))
    }
}
