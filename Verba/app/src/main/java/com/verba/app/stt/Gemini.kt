package com.verba.app.stt

import android.content.Context
import android.util.Base64
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.verba.app.data.GeminiModels
import com.verba.app.data.Prefs
import com.verba.app.data.Secrets
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GeminiException(message: String) : Exception(message)

/** No Gemini key saved, but Gemini is the selected model. */
class NoGeminiKeyException : Exception("Add your Gemini API key in Settings")

/**
 * Minimal Gemini API client for speech-to-text. Audio goes straight from the
 * phone to Google with the user's own key; nothing passes through any other
 * server.
 */
object GeminiClient {
    private const val API = "https://generativelanguage.googleapis.com"

    private fun prompt(language: String, words: List<String>): String = buildString {
        append("Transcribe this audio recording exactly as spoken. ")
        when (language) {
            "hi" -> append("The speaker speaks Hindi, sometimes with English words. ")
            "en" -> append("The speaker speaks English, sometimes with Hindi words. ")
            else -> append("The speaker may use Hindi, English, or both mixed in one sentence (Hinglish). ")
        }
        append("Write Hindi words in Devanagari script and English words in English (Latin letters). ")
        append("Do not translate, summarise or correct what was said. Use normal punctuation and write numbers as digits when the speaker says amounts, times or dates. ")
        if (words.isNotEmpty()) append("Names and words that may come up: ").append(words.joinToString(", ")).append(". ")
        append("Reply with the transcript only: no quotes, labels or comments. If nobody speaks, reply with nothing.")
    }

    /** Transcribe one stretch of 16 kHz mono audio. Blocking; call off the main thread. */
    fun transcribe(key: String, modelId: String, samples: FloatArray, language: String, words: List<String>): String {
        val model = GeminiModels.byId(modelId)
        val wav = wav(samples)
        val gen = JSONObject()
        val parts = JSONArray()
        var uploaded: String? = null
        try {
            if (model.dedicated) {
                val file = upload(key, wav)
                uploaded = file.first
                parts.put(JSONObject().put("fileData", JSONObject().put("fileUri", file.second).put("mimeType", "audio/wav")))
                val cfg = JSONObject().put("mode", "VERBATIM")
                cfg.put("languageCodes", when (language) {
                    "hi" -> JSONArray().put("hi-IN")
                    "en" -> JSONArray().put("en-IN")
                    else -> JSONArray().put("hi-IN").put("en-IN")
                })
                if (words.isNotEmpty()) cfg.put("customVocabulary", JSONArray(words.take(100)))
                gen.put("audioTranscriptionConfig", cfg)
            } else {
                parts.put(JSONObject().put("text", prompt(language, words)))
                parts.put(JSONObject().put("inlineData", JSONObject()
                    .put("mimeType", "audio/wav")
                    .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
                if (model.id.startsWith("gemini-3.8")) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
                gen.put("maxOutputTokens", 16384)
            }
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", gen)
            val resp = request("POST", "$API/v1beta/models/${model.id}:generateContent", key, body.toString().toByteArray())
            return clean(parseText(JSONObject(resp)))
        } finally {
            uploaded?.let { name -> runCatching { request("DELETE", "$API/v1beta/$name", key, null) } }
        }
    }

    /** Checks the key and model with one second of silence. Returns null when it works, else the problem. */
    fun test(key: String, modelId: String): String? = try {
        transcribe(key, modelId, FloatArray(16000), "auto", emptyList())
        null
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    // ------------------------------------------------------------------ Files API
    /** Uploads a WAV and returns (file name, file uri). */
    private fun upload(key: String, wav: ByteArray): Pair<String, String> {
        val start = open("POST", "$API/upload/v1beta/files", key).apply {
            setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            setRequestProperty("X-Goog-Upload-Command", "start")
            setRequestProperty("X-Goog-Upload-Header-Content-Length", wav.size.toString())
            setRequestProperty("X-Goog-Upload-Header-Content-Type", "audio/wav")
            setRequestProperty("Content-Type", "application/json")
        }
        val r0 = send(start, JSONObject().put("file", JSONObject().put("display_name", "verba-note")).toString().toByteArray(),
            header = "X-Goog-Upload-URL")
        if (r0.code !in 200..299) throw error(r0.code, r0.body)
        val uploadUrl = r0.header ?: throw GeminiException("Gemini did not accept the upload")
        val put = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000; readTimeout = 120000
            setRequestProperty("X-Goog-Upload-Offset", "0")
            setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            setRequestProperty("Content-Type", "audio/wav")
        }
        val r1 = send(put, wav)
        if (r1.code !in 200..299) throw error(r1.code, r1.body)
        var file = JSONObject(r1.body).getJSONObject("file")
        val name = file.getString("name")
        var waited = 0
        while (file.optString("state", "ACTIVE") == "PROCESSING" && waited < 30000) {
            Thread.sleep(1000); waited += 1000
            file = JSONObject(request("GET", "$API/v1beta/$name", key, null))
        }
        if (file.optString("state") == "FAILED") throw GeminiException("Gemini could not read the recording")
        return name to file.getString("uri")
    }

    // ------------------------------------------------------------------ HTTP
    private fun open(method: String, url: String, key: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 120000
            setRequestProperty("x-goog-api-key", key)
        }

    /** Returns the response body; throws a readable [GeminiException] for HTTP errors. */
    private fun request(method: String, url: String, key: String, body: ByteArray?): String {
        val c = open(method, url, key)
        if (body != null) c.setRequestProperty("Content-Type", "application/json")
        val r = send(c, body)
        if (r.code !in 200..299) throw error(r.code, r.body)
        return r.body
    }

    private class Resp(val code: Int, val body: String, val header: String?)

    private fun send(c: HttpURLConnection, body: ByteArray?, header: String? = null): Resp = try {
        if (body != null) {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val h = header?.let { c.getHeaderField(it) }
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        Resp(code, text, h)
    } catch (e: UnknownHostException) {
        throw GeminiException("No internet connection")
    } catch (e: SocketTimeoutException) {
        throw GeminiException("Gemini took too long to answer; check your connection")
    } catch (e: IOException) {
        throw GeminiException("Network problem: ${e.message}")
    } finally {
        c.disconnect()
    }

    private fun error(code: Int, body: String): GeminiException {
        val msg = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull().orEmpty()
        return GeminiException(when {
            code == 400 && msg.contains("API key", ignoreCase = true) -> "Gemini says this API key is not valid"
            code == 401 || code == 403 -> "Gemini refused the key" + if (msg.isNotEmpty()) ": $msg" else ""
            code == 404 -> "This Gemini model is not available to your key" + if (msg.isNotEmpty()) ": $msg" else ""
            code == 429 -> "Gemini's limit for your key was reached; try again in a minute"
            code >= 500 -> "Gemini is having trouble right now (error $code); try again"
            else -> "Gemini error $code" + if (msg.isNotEmpty()) ": $msg" else ""
        })
    }

    // ------------------------------------------------------------------ response
    private fun parseText(o: JSONObject): String {
        o.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let {
            throw GeminiException("Gemini would not transcribe this recording ($it)")
        }
        val cand = o.optJSONArray("candidates")?.optJSONObject(0) ?: return ""
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        val text = StringBuilder()
        val words = StringBuilder()
        if (parts != null) for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optBoolean("thought")) continue
            part.optString("text").takeIf { it.isNotEmpty() }?.let { text.append(it) }
            part.optJSONObject("audioTranscription")?.optJSONArray("words")?.let { w ->
                for (j in 0 until w.length()) {
                    val word = w.optJSONObject(j)?.optString("word").orEmpty()
                    if (word.isNotEmpty()) { if (words.isNotEmpty()) words.append(' '); words.append(word) }
                }
            }
        }
        if (text.isNotBlank()) return text.toString()
        if (words.isNotBlank()) return words.toString()
        val reason = cand.optString("finishReason")
        if (reason in setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")) {
            throw GeminiException("Gemini would not transcribe this recording ($reason)")
        }
        return ""
    }

    private val NO_SPEECH = Regex("""^[\[(]?\s*(no speech|silence|inaudible|no audio|nothing)\b.*[\])]?\.?$""", RegexOption.IGNORE_CASE)

    fun clean(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("```")) t = t.trim('`').removePrefix("text").trim()
        t = t.removePrefix("Transcript:").removePrefix("transcript:").trim()
        if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '“' && t.last() == '”'))) {
            t = t.substring(1, t.length - 1).trim()
        }
        if (NO_SPEECH.matches(t)) return ""
        return t
    }

    /** 16-bit PCM WAV, 16 kHz mono. */
    fun wav(samples: FloatArray): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in samples) data.putShort((x.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        val n = data.capacity()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + n); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(SR); h.putInt(SR * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(n)
        return ByteArrayOutputStream(44 + n).apply { write(h.array()); write(data.array()) }.toByteArray()
    }
}

/**
 * Cloud transcription with the user's own Gemini API key. Dictation is cut at
 * pauses (voice-activity detector bundled with the app) and each sentence is
 * sent as it ends; recordings go up in chunks of up to four minutes.
 */
class GeminiEngine(private val app: Context) : PauseEngine() {
    override val modelId = Prefs.GEMINI

    override fun vad(): Vad = Vad(
        app.assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.6f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 30f,
            ),
            sampleRate = SR,
            numThreads = 1,
        ),
    )

    private fun words(): List<String> =
        Prefs.geminiWords(app).split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    override fun decode(samples: FloatArray, language: String): String {
        val key = Secrets.geminiKey(app) ?: throw NoGeminiKeyException()
        return GeminiClient.transcribe(key, Prefs.geminiModel(app), samples, language, words())
    }

    override fun transcribe(samples: FloatArray, language: String, progress: (Float) -> Unit): List<String> {
        val out = mutableListOf<String>()
        val chunks = splitAtQuietPoints(samples, maxSeconds = 240, searchSeconds = 8)
        var done = 0
        for ((a, b) in chunks) {
            progress(done / samples.size.toFloat())
            decode(samples.copyOfRange(a, b), language).takeIf { it.isNotBlank() }?.let(out::add)
            done = b
        }
        progress(1f)
        return out
    }

    override fun close() {}

    companion object {
        /** Chunk boundaries (start, end) no longer than [maxSeconds], cut at the quietest 100 ms nearby. */
        fun splitAtQuietPoints(x: FloatArray, maxSeconds: Int, searchSeconds: Int): List<Pair<Int, Int>> {
            val max = maxSeconds * SR
            if (x.size <= max) return listOf(0 to x.size)
            val win = SR / 10
            val out = mutableListOf<Pair<Int, Int>>()
            var start = 0
            while (x.size - start > max) {
                val target = start + max
                var best = target
                var bestE = Double.MAX_VALUE
                var w = (target - searchSeconds * SR).coerceAtLeast(start + SR)
                while (w + win <= target) {
                    var e = 0.0
                    for (i in w until w + win) e += x[i] * x[i]
                    if (e < bestE) { bestE = e; best = w + win / 2 }
                    w += win / 2
                }
                out += start to best
                start = best
            }
            out += start to x.size
            return out
        }
    }
}
