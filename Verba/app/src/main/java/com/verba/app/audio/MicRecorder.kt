package com.verba.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 16 kHz mono microphone capture.
 *
 * Every 100 ms chunk goes to [onChunk] (on the capture thread) as floats in
 * -1..1, and optionally into a WAV file. [onLevel] gets a 0..1 loudness value on
 * the main thread for meters and animations.
 */
class MicRecorder(
    private val wavFile: File? = null,
    private val source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    private val onChunk: ((FloatArray) -> Unit)? = null,
    private val onLevel: ((Float) -> Unit)? = null,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var wav: WavWriter? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile var paused = false
        private set
    @Volatile var samples = 0L
        private set

    val elapsedMs: Long get() = samples * 1000 / SAMPLE_RATE

    /** Returns false if the microphone could not be opened. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SAMPLE_RATE * 2))
        } catch (e: SecurityException) {
            return false
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release(); return false
        }
        try {
            wav = wavFile?.let { WavWriter(it, SAMPLE_RATE) }
            rec.startRecording()
        } catch (e: Exception) {
            rec.release(); wav?.close(); return false
        }
        record = rec
        running = true
        thread = Thread({ loop(rec) }, "verba-mic").apply { start() }
        return true
    }

    private fun loop(rec: AudioRecord) {
        val buf = ShortArray(SAMPLE_RATE / 10)
        var lastLevel = 0L
        while (running) {
            if (paused) {
                Thread.sleep(40)
                continue
            }
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) {
                // stop() during pause makes read() return early or with an error code
                if (n == 0 || paused) continue else break
            }
            wav?.write(buf, n)
            samples += n
            val f = FloatArray(n)
            var sum = 0.0
            for (i in 0 until n) {
                val v = buf[i] / 32768f
                f[i] = v
                sum += v * v
            }
            onChunk?.invoke(f)
            val now = System.nanoTime()
            if (onLevel != null && now - lastLevel > 50_000_000) {
                lastLevel = now
                // perceptual-ish 0..1 level from RMS
                val rms = sqrt(sum / n).toFloat()
                val level = (20 * kotlin.math.log10(rms + 1e-6f) + 60f) / 50f
                main.post { onLevel.invoke(level.coerceIn(0f, 1f)) }
            }
        }
    }

    /** Pause capture: the microphone is released and nothing is written until [resume]. */
    fun pause() {
        if (!running || paused) return
        paused = true
        record?.let { runCatching { it.stop() } }
        onLevel?.let { cb -> main.post { cb(0f) } }
    }

    /** Continue after [pause]. Returns false if the microphone could not be reopened. */
    fun resume(): Boolean {
        if (!running || !paused) return true
        return try {
            record?.startRecording()
            paused = false
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Stops capture and closes the WAV file. Returns the recorded length in ms. */
    fun stop(): Long {
        running = false
        thread?.join(1500)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        wav?.close()
        wav = null
        return elapsedMs
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}

/** Minimal streaming PCM16 WAV writer; the header sizes are fixed up on close. */
class WavWriter(file: File, private val sampleRate: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private val bb = ByteBuffer.allocate(MicRecorder.SAMPLE_RATE * 2).order(ByteOrder.LITTLE_ENDIAN)

    init {
        raf.setLength(0)
        raf.write(header(0))
    }

    fun write(samples: ShortArray, n: Int) {
        bb.clear()
        for (i in 0 until n) bb.putShort(samples[i])
        raf.write(bb.array(), 0, n * 2)
        dataBytes += n * 2
    }

    fun close() {
        try {
            raf.seek(0)
            raf.write(header(dataBytes))
        } finally {
            raf.close()
        }
    }

    private fun header(data: Long): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + data).toInt())
        h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
        h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(sampleRate); h.putInt(sampleRate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(data.toInt())
        return h.array()
    }
}
