package com.verba.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class Note(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val audioPath: String?,
    val durationMs: Long,
    val lang: String,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val displayTitle: String,
    val preview: String,
) {
    val isVoice get() = kind == "voice"

    companion object {
        fun from(o: JSONObject) = Note(
            id = o.getString("id"),
            kind = o.getString("kind"),
            title = o.optString("title"),
            body = o.optString("body"),
            audioPath = if (o.isNull("audio_path")) null else o.getString("audio_path"),
            durationMs = o.optLong("duration_ms"),
            lang = o.optString("lang"),
            source = o.optString("source"),
            createdAt = o.optLong("created_at"),
            updatedAt = o.optLong("updated_at"),
            deletedAt = if (o.isNull("deleted_at")) null else o.getLong("deleted_at"),
            displayTitle = o.optString("display_title"),
            preview = o.optString("preview"),
        )
    }
}

data class Counts(val all: Int, val text: Int, val voice: Int, val trash: Int)

data class ModelInfo(
    val id: String,
    val name: String,
    val vendor: String,
    val mode: String,
    val tagline: String,
    val detail: String,
    val license: String,
    val ramMb: Int,
    val downloadBytes: Long,
    val installedMb: Int,
    val installed: Boolean,
    val partialBytes: Long,
)

/**
 * Bridge to the Python backend (``verba.api``) running inside the app via
 * Chaquopy. All calls block, so make them from [io] (or another background
 * thread); [async] does that and hands the result back on the main thread.
 */
object Backend {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    /** One thread for note reads/writes keeps them in order. */
    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "verba-io") }

    @Volatile
    private var module: PyObject? = null

    fun init(context: Context) {
        app = context.applicationContext
        io.execute { runCatching { api() } }       // warm Python up early
    }

    @Synchronized
    private fun api(): PyObject {
        module?.let { return it }
        if (!Python.isStarted()) Python.start(AndroidPlatform(app))
        val m = Python.getInstance().getModule("verba.api")
        m.callAttr("init", app.filesDir.absolutePath)
        module = m
        return m
    }

    private fun call(name: String, vararg args: Any?): PyObject? = api().callAttr(name, *args)
    private fun str(name: String, vararg args: Any?): String = call(name, *args)?.toString() ?: ""

    fun <T> async(work: () -> T, done: (Result<T>) -> Unit) {
        io.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }

    // ------------------------------------------------------------- notes
    private fun notes(json: String): List<Note> {
        val a = JSONArray(json)
        return List(a.length()) { Note.from(a.getJSONObject(it)) }
    }

    fun listNotes(kind: String = "all", query: String = ""): List<Note> = notes(str("list_notes", kind, query))
    fun listTrash(): List<Note> = notes(str("list_trash"))
    fun getNote(id: String): Note = Note.from(JSONObject(str("get_note", id)))

    fun counts(): Counts {
        val o = JSONObject(str("counts"))
        return Counts(o.optInt("all"), o.optInt("text"), o.optInt("voice"), o.optInt("trash"))
    }

    fun createText(title: String, body: String, source: String): Note =
        Note.from(JSONObject(str("create_text", title, body, source)))

    fun createVoice(audioPath: String, durationMs: Long, title: String = ""): Note =
        Note.from(JSONObject(str("create_voice", audioPath, durationMs, title)))

    fun updateNote(id: String, title: String, body: String, source: String = ""): Note =
        Note.from(JSONObject(str("update_note", id, title, body, source)))

    fun discardIfEmpty(id: String): Boolean = call("discard_if_empty", id)?.toBoolean() ?: false

    /** Delete = move to Recently Deleted. */
    fun trash(id: String): Note = Note.from(JSONObject(str("trash", id)))
    fun restore(id: String): Note = Note.from(JSONObject(str("restore", id)))
    fun deleteForever(id: String) { call("delete_forever", id) }
    fun emptyTrash(): Int = call("empty_trash")?.toInt() ?: 0
    fun cleanupOrphans(): Int = call("cleanup_orphans")?.toInt() ?: 0

    data class WidgetNote(val id: String, val kind: String, val title: String, val preview: String, val durationMs: Long, val updatedAt: Long)

    fun widgetNotes(limit: Int = 30): List<WidgetNote> {
        val a = JSONArray(str("widget_notes", limit))
        return List(a.length()) {
            val o = a.getJSONObject(it)
            WidgetNote(o.getString("id"), o.getString("kind"), o.optString("title"), o.optString("preview"),
                o.optLong("duration_ms"), o.optLong("updated_at"))
        }
    }

    fun audioDir(): String = str("audio_dir")

    // ------------------------------------------------------------- text
    fun cleanSegment(text: String, final: Boolean = true): String = str("clean_segment", text, final)
    fun joinText(before: String, segment: String): String = str("join_text", before, segment)

    // ------------------------------------------------------------- models
    fun modelStatus(): Pair<List<ModelInfo>, Long> {
        val o = JSONObject(str("model_status"))
        val a = o.getJSONArray("models")
        val list = List(a.length()) {
            val m = a.getJSONObject(it)
            ModelInfo(
                id = m.getString("id"), name = m.getString("name"), vendor = m.getString("vendor"),
                mode = m.getString("mode"), tagline = m.getString("tagline"), detail = m.getString("detail"),
                license = m.getString("license"), ramMb = m.getInt("ram_mb"),
                downloadBytes = m.getLong("download_bytes"), installedMb = m.getInt("installed_mb"),
                installed = m.getBoolean("installed"),
                partialBytes = m.getLong("partial_bytes"),
            )
        }
        return list to o.getLong("free_bytes")
    }

    fun installedModelIds(): List<String> = modelStatus().first.filter { it.installed }.map { it.id }

    fun modelPaths(id: String): Map<String, String> {
        val o = JSONObject(str("model_paths", id))
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    /** Blocking. Returns "" on success, "cancelled", or an error message. */
    fun installModel(id: String, sink: ProgressSink): String = str("install_model", id, sink)

    fun deleteModel(id: String) { call("delete_model", id) }
}

/** Called from Python during a model download. Must stay a public interface for Chaquopy. */
interface ProgressSink {
    fun onProgress(phase: String, done: Long, total: Long)
    fun isCancelled(): Boolean
}
