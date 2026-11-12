package com.verba.app.stt

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.verba.app.data.Backend
import com.verba.app.data.Note
import com.verba.app.data.Prefs
import com.verba.app.service.TranscribeService
import com.verba.app.widget.NotesWidgetProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class NoSpeechException : Exception("No speech was found in this recording")

/**
 * Turns saved voice notes into text in the background and stores the text on
 * the note.
 *
 * Every transcription (automatic after recording, from the widget, or "Redo")
 * goes through here, so it keeps going after you leave the screen: it runs in
 * a foreground service, and any screen showing the note is told when the text
 * is ready. Notes are also remembered until they finish, so a transcription
 * cut off by the system is picked up again the next time the app is opened.
 */
object AutoTranscriber {
    /** Called on the main thread. */
    interface Listener {
        fun onProgress(noteId: String, fraction: Float) {}
        /** [note] is the saved note when text was stored; [error] is set when it failed. */
        fun onFinished(noteId: String, note: Note?, error: Throwable?) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val running = ConcurrentHashMap<String, Float>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    fun isRunning(noteId: String) = running.containsKey(noteId)
    fun progress(noteId: String): Float? = running[noteId]

    /**
     * Transcribe a saved voice note. [replace] overwrites text the note already
     * has (Redo); otherwise text is only stored if the note is still empty.
     */
    fun run(ctx: Context, noteId: String, audioPath: String, replace: Boolean = false) {
        val app = ctx.applicationContext
        if (running.putIfAbsent(noteId, 0f) != null) return          // already on it
        val attempts = (Prefs.pendingTranscriptions(app)[noteId] ?: 0) + 1
        Prefs.setPendingTranscription(app, noteId, attempts)
        main.post { listeners.forEach { it.onProgress(noteId, 0f) } }
        NotesWidgetProvider.refresh(app)
        if (!TranscribeService.start(app, noteId, audioPath, replace)) {
            // The system did not allow a foreground service right now: run in the app's process instead.
            execute(app, noteId, audioPath, replace) {}
        }
    }

    /** Does the work; [onDone] runs on the main thread. Used by [TranscribeService]. */
    internal fun execute(app: Context, noteId: String, audioPath: String, replace: Boolean, onDone: () -> Unit) {
        running.putIfAbsent(noteId, 0f)
        SttManager.transcribeFile(app, audioPath, progress = { f ->
            running[noteId] = f
            listeners.forEach { it.onProgress(noteId, f) }
        }) { r ->
            r.onFailure { e -> finish(app, noteId, null, e, onDone) }
            r.onSuccess { sentences ->
                if (sentences.isEmpty()) {
                    finish(app, noteId, null, NoSpeechException(), onDone)
                    return@onSuccess
                }
                Backend.async({
                    var text = ""
                    for (s in sentences) text += Dictation.prepare(text, s)
                    val note = Backend.getNote(noteId)
                    if (replace || note.body.isBlank()) Backend.updateNote(noteId, note.title, text) else note
                }) { u -> finish(app, noteId, u.getOrNull(), u.exceptionOrNull(), onDone) }
            }
        }
    }

    private fun finish(app: Context, noteId: String, note: Note?, error: Throwable?, onDone: () -> Unit) {
        running.remove(noteId)
        Prefs.setPendingTranscription(app, noteId, null)
        NotesWidgetProvider.refresh(app)
        listeners.forEach { it.onFinished(noteId, note, error) }
        onDone()
    }

    /**
     * Start again any transcription that was cut off (the system closed the
     * app before it finished). Call from a screen the user is looking at.
     */
    fun resumePending(ctx: Context) {
        val app = ctx.applicationContext
        val pending = Prefs.pendingTranscriptions(app).filterKeys { !isRunning(it) }
        if (pending.isEmpty()) return
        Backend.async({
            pending.keys.associateWith { id -> runCatching { Backend.getNote(id) }.getOrNull() }
        }) { r ->
            val notes = r.getOrNull() ?: return@async
            for ((id, n) in notes) {
                val path = n?.audioPath
                val tries = pending[id] ?: 0
                if (n == null || path == null || n.deletedAt != null || n.body.isNotBlank() || tries >= 3) {
                    Prefs.setPendingTranscription(app, id, null)
                } else if (!isRunning(id)) {
                    run(app, id, path)
                }
            }
        }
    }
}
