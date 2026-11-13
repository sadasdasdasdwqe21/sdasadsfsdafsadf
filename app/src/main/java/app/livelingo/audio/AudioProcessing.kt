package app.livelingo.audio

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** 16 kHz mono PCM16: what every speech engine in the app consumes. */
const val PIPELINE_SAMPLE_RATE = 16_000

/** 100 ms of audio. Small enough for low latency, large enough to avoid per-fragment overhead. */
const val CHUNK_MS = 100

class AudioChunk(
    val samples: ShortArray,
    /** elapsedRealtime() when the last sample of this chunk was captured. */
    val captureTimeMs: Long,
    val sampleRate: Int = PIPELINE_SAMPLE_RATE,
) {
    val durationMs: Int get() = samples.size * 1000 / sampleRate

    fun toLittleEndianBytes(): ByteArray {
        val out = ByteArray(samples.size * 2)
        var j = 0
        for (s in samples) {
            out[j++] = (s.toInt() and 0xFF).toByte()
            out[j++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }
}

object AudioMath {
    /** RMS level in dBFS. Digital silence returns -96. */
    fun levelDb(samples: ShortArray): Float {
        if (samples.isEmpty()) return -96f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        val rms = sqrt(sum / samples.size)
        if (rms < 1.0) return -96f
        return (20.0 * log10(rms / 32768.0)).toFloat()
    }

    fun isDigitalSilence(samples: ShortArray): Boolean {
        for (s in samples) if (s > 1 || s < -1) return false
        return true
    }
}

/**
 * Converts captured audio to 16 kHz mono. Integer ratios (48 kHz, 32 kHz) use box-filter
 * decimation, which also acts as a simple anti-alias filter. Other rates use linear interpolation.
 */
object Resampler {
    fun to16k(input: ShortArray, inRate: Int): ShortArray {
        if (inRate == PIPELINE_SAMPLE_RATE || input.isEmpty()) return input
        if (inRate % PIPELINE_SAMPLE_RATE == 0) {
            val factor = inRate / PIPELINE_SAMPLE_RATE
            val out = ShortArray(input.size / factor)
            for (i in out.indices) {
                var acc = 0
                val base = i * factor
                for (k in 0 until factor) acc += input[base + k]
                out[i] = (acc / factor).toShort()
            }
            return out
        }
        val outLen = (input.size.toLong() * PIPELINE_SAMPLE_RATE / inRate).toInt()
        val out = ShortArray(outLen)
        val step = inRate.toDouble() / PIPELINE_SAMPLE_RATE
        for (i in 0 until outLen) {
            val pos = i * step
            val idx = pos.toInt()
            val frac = pos - idx
            val a = input[idx.coerceAtMost(input.size - 1)]
            val b = input[(idx + 1).coerceAtMost(input.size - 1)]
            out[i] = (a + (b - a) * frac).toInt().toShort()
        }
        return out
    }
}

data class VadResult(val speech: Boolean, val rawSpeech: Boolean, val levelDb: Float, val noiseFloorDb: Float)

/**
 * Energy voice activity detector with an adaptive noise floor and a hangover period, so short
 * pauses inside a sentence do not cut the stream.
 */
class VoiceActivityDetector(
    sensitivity: Float = 0.6f,
    private val hangoverMs: Int = 1_300,
) {
    private val margin = lerp(14f, 6f, sensitivity.coerceIn(0f, 1f))
    private val absoluteMin = lerp(-44f, -58f, sensitivity.coerceIn(0f, 1f))
    private var noiseFloor = -65f
    private var holdMs = 0

    fun process(samples: ShortArray, durationMs: Int): VadResult {
        val db = AudioMath.levelDb(samples)
        val raw = db > noiseFloor + margin && db > absoluteMin
        if (db < noiseFloor) {
            noiseFloor = noiseFloor * 0.7f + db * 0.3f          // fall quickly
        } else if (!raw) {
            noiseFloor = noiseFloor * 0.95f + db * 0.05f        // rise slowly with background noise
        }
        noiseFloor = noiseFloor.coerceIn(-90f, -25f)
        holdMs = if (raw) hangoverMs else max(0, holdMs - durationMs)
        return VadResult(speech = raw || holdMs > 0, rawSpeech = raw, levelDb = db, noiseFloorDb = noiseFloor)
    }

    fun reset() { holdMs = 0 }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/**
 * Bounded hand-off between the capture thread and the network sender. When the sender falls
 * behind, the oldest audio is dropped so delay can never pile up. Drops are counted.
 */
class AudioBufferManager(val capacity: Int = 40) {
    private val queue = ArrayBlockingQueue<AudioChunk>(capacity)
    private val dropped = AtomicLong(0)

    val droppedCount: Long get() = dropped.get()
    val size: Int get() = queue.size

    fun offer(chunk: AudioChunk) {
        while (!queue.offer(chunk)) {
            if (queue.poll() != null) dropped.incrementAndGet()
        }
    }

    fun poll(timeoutMs: Long): AudioChunk? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** Discards audio older than [maxAgeMs]. Used after a reconnect so stale speech is skipped. */
    fun dropOlderThan(nowMs: Long, maxAgeMs: Long): Int {
        var n = 0
        while (true) {
            val head = queue.peek() ?: break
            if (nowMs - head.captureTimeMs <= maxAgeMs) break
            if (queue.poll() != null) { n++; dropped.incrementAndGet() }
        }
        return n
    }

    fun clear() = queue.clear()
}

/** Keeps the last few hundred milliseconds so the start of a sentence is not clipped by the VAD. */
class PreRollBuffer(private val maxMs: Int = 400) {
    private val chunks = ArrayDeque<AudioChunk>()
    private var totalMs = 0

    fun add(chunk: AudioChunk) {
        chunks.addLast(chunk)
        totalMs += chunk.durationMs
        while (totalMs > maxMs && chunks.size > 1) totalMs -= chunks.removeFirst().durationMs
    }

    fun drain(): List<AudioChunk> {
        val out = chunks.toList()
        chunks.clear()
        totalMs = 0
        return out
    }
}
