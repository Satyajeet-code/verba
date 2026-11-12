package com.verba.app.stt

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.verba.app.data.Backend
import com.verba.app.data.Prefs
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

class NoModelException : Exception("No speech model is downloaded yet")

/** Partial-text markers from pause-based models (they have no words until a pause). */
const val PARTIAL_SPEAKING = "\u0000speaking"
const val PARTIAL_WRITING = "\u0000writing"

/** Callbacks arrive on a background thread. */
interface SessionCallback {
    /** Text of the sentence currently being spoken ("" when nothing is pending). */
    fun onPartial(text: String)
    /** A finished sentence (raw model output; clean it before inserting). */
    fun onSegment(text: String)
    fun onFinished()
    fun onError(e: Throwable)
}

interface SttSession {
    /** 16 kHz mono samples from the microphone thread. */
    fun accept(samples: FloatArray)
    /** No more audio: flush and call onFinished(). */
    fun finish()
    fun cancel()
}

interface SttEngine : Closeable {
    val modelId: String
    /** True when words appear while speaking; false when text arrives after each pause. */
    val live: Boolean
    fun newSession(language: String, cb: SessionCallback): SttSession
    /** Transcribe a whole recording; returns raw sentences in order. */
    fun transcribe(samples: FloatArray, language: String, progress: (Float) -> Unit): List<String>
}

/**
 * True if every letter is Latin or Devanagari. In "Auto" mode the models can
 * mistake short or noisy Hindi for another language (e.g. Urdu script or
 * Chinese); such text is re-decoded with Hindi pinned.
 */
internal fun isHindiOrEnglish(text: String): Boolean = text.all { c ->
    !Character.isLetter(c) || c in 'A'..'Z' || c in 'a'..'z' || c in '\u00C0'..'\u024F' ||
        c in '\u0900'..'\u097F' || c in '\uA8E0'..'\uA8FF'
}

private fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

internal const val SR = 16000

// ---------------------------------------------------------------------------- Nemotron (live)
/**
 * NVIDIA Nemotron 3.5 streaming transducer. Language is pinned per stream with
 * setOption("language", "hi" | "en"); left unset it auto-detects.
 */
class NemotronEngine(paths: Map<String, String>) : SttEngine {
    override val modelId = paths.getValue("id")
    override val live = true

    private val recognizer = OnlineRecognizer(
        null,
        OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SR, featureDim = 128),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = paths.getValue("encoder"),
                    decoder = paths.getValue("decoder"),
                    joiner = paths.getValue("joiner"),
                ),
                tokens = paths.getValue("tokens"),
                numThreads = threads(),
                provider = "cpu",
                modelType = "",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 1.0f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 25.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ),
    )

    private fun stream(language: String): OnlineStream {
        val s = recognizer.createStream()
        if (language == "hi" || language == "en") s.setOption("language", language)
        // Without a little silence first, the first word is often lost.
        s.acceptWaveform(FloatArray(SR * 4 / 10), SR)
        return s
    }

    private fun drain(s: OnlineStream) {
        while (recognizer.isReady(s)) recognizer.decode(s)
    }

    /** Re-run one finished sentence with Hindi pinned (see [isHindiOrEnglish]). */
    private fun redecodeHindi(chunks: List<FloatArray>): String {
        val s = stream("hi")
        try {
            for (c in chunks) { s.acceptWaveform(c, SR); drain(s) }
            s.acceptWaveform(FloatArray(SR * 8 / 10), SR)
            s.inputFinished()
            drain(s)
            return recognizer.getResult(s).text.trim()
        } finally {
            s.release()
        }
    }

    private fun checked(text: String, language: String, chunks: List<FloatArray>): String =
        if (language != "auto" || text.isEmpty() || isHindiOrEnglish(text)) text
        else redecodeHindi(chunks).ifEmpty { text }

    override fun newSession(language: String, cb: SessionCallback): SttSession = object : SttSession {
        private val queue = LinkedBlockingQueue<FloatArray>()
        private val end = FloatArray(0)
        private val stop = FloatArray(0)

        init {
            Thread({
                SttManager.busy.incrementAndGet()
                val s = stream(language)
                var last = ""
                val heard = ArrayList<FloatArray>()      // audio of the current sentence
                try {
                    while (true) {
                        val chunk = queue.take()
                        if (chunk === stop) break
                        if (chunk === end) {
                            s.acceptWaveform(FloatArray(SR * 8 / 10), SR)
                            s.inputFinished()
                            drain(s)
                            val t = checked(recognizer.getResult(s).text.trim(), language, heard)
                            if (t.isNotEmpty()) cb.onSegment(t)
                            cb.onPartial("")
                            cb.onFinished()
                            break
                        }
                        heard += chunk
                        s.acceptWaveform(chunk, SR)
                        drain(s)
                        val t = recognizer.getResult(s).text.trim()
                        if (recognizer.isEndpoint(s)) {
                            val final = checked(t, language, heard)
                            if (final.isNotEmpty()) cb.onSegment(final)
                            recognizer.reset(s)
                            heard.clear()
                            last = ""
                            cb.onPartial("")
                        } else if (t != last) {
                            last = t
                            cb.onPartial(t)
                        }
                    }
                } catch (e: Throwable) {
                    cb.onError(e)
                } finally {
                    s.release()
                    SttManager.busy.decrementAndGet()
                }
            }, "verba-nemotron").start()
        }

        override fun accept(samples: FloatArray) { queue.put(samples) }
        override fun finish() { queue.put(end) }
        override fun cancel() { queue.put(stop) }
    }

    override fun transcribe(samples: FloatArray, language: String, progress: (Float) -> Unit): List<String> {
        val out = mutableListOf<String>()
        val s = stream(language)
        try {
            val step = SR / 2
            val heard = ArrayList<FloatArray>()
            var i = 0
            while (i < samples.size) {
                val j = minOf(samples.size, i + step)
                val chunk = samples.copyOfRange(i, j)
                heard += chunk
                s.acceptWaveform(chunk, SR)
                drain(s)
                if (recognizer.isEndpoint(s)) {
                    checked(recognizer.getResult(s).text.trim(), language, heard).takeIf { it.isNotEmpty() }?.let(out::add)
                    recognizer.reset(s)
                    heard.clear()
                }
                i = j
                progress(i / samples.size.toFloat())
            }
            s.acceptWaveform(FloatArray(SR * 8 / 10), SR)
            s.inputFinished()
            drain(s)
            checked(recognizer.getResult(s).text.trim(), language, heard).takeIf { it.isNotEmpty() }?.let(out::add)
        } finally {
            s.release()
        }
        return out
    }

    override fun close() = recognizer.release()
}

// ---------------------------------------------------------------------------- Qwen3 (after pauses)
/**
 * Engines that write a sentence after each pause: a voice-activity detector
 * cuts the audio and [decode] turns each piece into text.
 */
abstract class PauseEngine : SttEngine {
    override val live = false
    protected abstract fun vad(): Vad
    /** Raw text for one stretch of speech ("" if nothing was said). */
    protected abstract fun decode(samples: FloatArray, language: String): String

    override fun newSession(language: String, cb: SessionCallback): SttSession = object : SttSession {
        private val queue = LinkedBlockingQueue<FloatArray>()
        private val end = FloatArray(0)
        private val stop = FloatArray(0)

        init {
            Thread({
                SttManager.busy.incrementAndGet()
                val v = vad()
                val window = 512
                var pending = FloatArray(0)
                var speaking = false
                fun popSegments() {
                    while (!v.empty()) {
                        val seg = v.front()
                        v.pop()
                        cb.onPartial(PARTIAL_WRITING)
                        val t = decode(seg.samples, language)
                        if (t.isNotEmpty()) cb.onSegment(t)
                    }
                }
                try {
                    while (true) {
                        val chunk = queue.take()
                        if (chunk === stop) break
                        if (chunk === end) {
                            v.flush()
                            popSegments()
                            cb.onPartial("")
                            cb.onFinished()
                            break
                        }
                        pending += chunk
                        var off = 0
                        while (pending.size - off >= window) {
                            v.acceptWaveform(pending.copyOfRange(off, off + window))
                            off += window
                        }
                        pending = pending.copyOfRange(off, pending.size)
                        val now = v.isSpeechDetected()
                        if (now != speaking) {
                            speaking = now
                            cb.onPartial(if (now) PARTIAL_SPEAKING else "")
                        }
                        popSegments()
                    }
                } catch (e: Throwable) {
                    cb.onError(e)
                } finally {
                    v.release()
                    SttManager.busy.decrementAndGet()
                }
            }, "verba-pause").start()
        }

        override fun accept(samples: FloatArray) { queue.put(samples) }
        override fun finish() { queue.put(end) }
        override fun cancel() { queue.put(stop) }
    }

    override fun transcribe(samples: FloatArray, language: String, progress: (Float) -> Unit): List<String> {
        val out = mutableListOf<String>()
        val v = vad()
        try {
            val window = 512
            var i = 0
            while (i + window <= samples.size) {
                v.acceptWaveform(samples.copyOfRange(i, i + window))
                i += window
                while (!v.empty()) {
                    val seg = v.front(); v.pop()
                    decode(seg.samples, language).takeIf { it.isNotEmpty() }?.let(out::add)
                    progress(i / samples.size.toFloat())
                }
            }
            v.flush()
            while (!v.empty()) {
                val seg = v.front(); v.pop()
                decode(seg.samples, language).takeIf { it.isNotEmpty() }?.let(out::add)
            }
            progress(1f)
        } finally {
            v.release()
        }
        return out
    }

}

/**
 * Qwen3-ASR 0.6B and its Hinglish fine-tunes. Not a streaming model, so a
 * voice-activity detector cuts the audio at pauses and each piece is
 * transcribed as soon as it ends.
 */
class Qwen3Engine(paths: Map<String, String>) : PauseEngine() {
    override val modelId = paths.getValue("id")
    private val vadPath = paths.getValue("vad")

    /**
     * Hinglish fine-tunes were trained with the prompt "language None", which
     * lets them write each word in its own script. They always get that prompt;
     * the Hindi / English setting only applies to the original Qwen3-ASR.
     */
    private val languageAgnostic = paths["prompt"] == "none"

    private val recognizer = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SR, featureDim = 128),
            modelConfig = OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = paths.getValue("conv_frontend"),
                    encoder = paths.getValue("encoder"),
                    decoder = paths.getValue("decoder"),
                    tokenizer = paths.getValue("tokenizer"),
                    maxNewTokens = 256,
                ),
                numThreads = threads(),
                provider = "cpu",
            ),
        ),
    )

    override fun vad(): Vad = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadPath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 20f,       // keeps memory use bounded
            ),
            sampleRate = SR,
            numThreads = 1,
        ),
    )

    /** [language]: "hi", "en", "none" (no language, Hinglish models) or "auto". */
    private fun decodeOnce(samples: FloatArray, language: String): String {
        val s = recognizer.createStream()
        try {
            when (language) {
                "hi" -> s.setOption("language", "Hindi")
                "en" -> s.setOption("language", "English")
                "none" -> s.setOption("language", "None")
            }
            s.acceptWaveform(samples, SR)
            recognizer.decode(s)
            return recognizer.getResult(s).text.trim()
        } finally {
            s.release()
        }
    }

    override fun decode(samples: FloatArray, language: String): String {
        val lang = if (languageAgnostic) "none" else language
        val t = decodeOnce(samples, lang)
        if (lang == "hi" || lang == "en" || t.isEmpty() || isHindiOrEnglish(t)) return t
        // Wrong script (e.g. Urdu or Chinese for noisy Hindi): try again with Hindi pinned.
        return decodeOnce(samples, "hi")
    }

    override fun close() = recognizer.release()
}

// ---------------------------------------------------------------------------- manager
/**
 * Keeps at most one model in memory (they need 1–1.6 GB) and loads it off the
 * main thread. Released when the app goes to the background.
 */
object SttManager {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "verba-stt") }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var engine: SttEngine? = null

    /** Sessions still decoding; a model is never freed while one is running. */
    internal val busy = java.util.concurrent.atomic.AtomicInteger()

    private fun waitIdle() {
        var waited = 0
        while (busy.get() > 0 && waited < 5000) { Thread.sleep(50); waited += 50 }
    }

    val loadedModelId: String? get() = engine?.modelId

    private fun load(ctx: Context): SttEngine {
        val wanted = Prefs.activeModel(ctx)
        if (wanted == Prefs.GEMINI) {
            if (!com.verba.app.data.Secrets.hasGeminiKey(ctx)) throw NoGeminiKeyException()
            engine?.let { if (it.modelId == Prefs.GEMINI) return it }
            waitIdle()
            engine?.close()
            return GeminiEngine(ctx.applicationContext).also { engine = it }
        }
        val installed = Backend.installedModelIds()
        val id = when {
            wanted != null && wanted in installed -> wanted
            installed.isNotEmpty() -> installed.first().also { Prefs.setActiveModel(ctx, it) }
            else -> throw NoModelException()
        }
        engine?.let { if (it.modelId == id) return it }
        waitIdle()
        engine?.close()
        engine = null
        val paths = Backend.modelPaths(id)
        val e = if (paths["mode"] == "live") NemotronEngine(paths) else Qwen3Engine(paths)
        engine = e
        return e
    }

    fun acquire(ctx: Context, cb: (Result<SttEngine>) -> Unit) {
        val app = ctx.applicationContext
        exec.execute {
            val r = runCatching { load(app) }
            main.post { cb(r) }
        }
    }

    /** Transcribe a WAV file with the active model. Callbacks on the main thread. */
    fun transcribeFile(
        ctx: Context,
        path: String,
        progress: (Float) -> Unit,
        done: (Result<List<String>>) -> Unit,
    ) {
        val app = ctx.applicationContext
        exec.execute {
            val r = runCatching {
                val e = load(app)
                val wave = com.k2fsa.sherpa.onnx.WaveReader.readWave(path)
                val samples = if (wave.sampleRate == SR) wave.samples else resample(wave.samples, wave.sampleRate)
                e.transcribe(samples, Prefs.language(app)) { f -> main.post { progress(f) } }
            }
            main.post { done(r) }
        }
    }

    fun release() {
        exec.execute {
            if (busy.get() == 0) {
                engine?.close()
                engine = null
            }
        }
    }

    private fun resample(x: FloatArray, from: Int): FloatArray {
        val n = (x.size.toLong() * SR / from).toInt()
        val out = FloatArray(n)
        val ratio = from.toDouble() / SR
        for (i in 0 until n) {
            val pos = i * ratio
            val j = pos.toInt().coerceAtMost(x.size - 1)
            val k = (j + 1).coerceAtMost(x.size - 1)
            val t = (pos - j).toFloat()
            out[i] = x[j] * (1 - t) + x[k] * t
        }
        return out
    }
}
