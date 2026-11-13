package app.livelingo

import app.livelingo.audio.AudioBufferManager
import app.livelingo.audio.AudioChunk
import app.livelingo.audio.AudioMath
import app.livelingo.audio.PreRollBuffer
import app.livelingo.audio.Resampler
import app.livelingo.audio.VoiceActivityDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioProcessingTest {

    private fun tone(amplitude: Double, samples: Int = 1600, freq: Double = 300.0, rate: Int = 16000) =
        ShortArray(samples) { (amplitude * 32767 * sin(2 * PI * freq * it / rate)).toInt().toShort() }

    @Test
    fun levelOfSilenceAndFullScale() {
        assertEquals(-96f, AudioMath.levelDb(ShortArray(1600)), 0.01f)
        val full = AudioMath.levelDb(tone(1.0))
        assertTrue("full-scale sine is about -3 dBFS, was $full", full in -3.5f..-2.5f)
        assertTrue(AudioMath.isDigitalSilence(ShortArray(100)))
        assertFalse(AudioMath.isDigitalSilence(tone(0.1)))
    }

    @Test
    fun resamples48kTo16k() {
        val input = tone(0.5, samples = 4800, rate = 48000)
        val out = Resampler.to16k(input, 48000)
        assertEquals(1600, out.size)
        val inLevel = AudioMath.levelDb(input)
        val outLevel = AudioMath.levelDb(out)
        assertTrue("300 Hz tone keeps its level ($inLevel vs $outLevel)", kotlin.math.abs(inLevel - outLevel) < 1f)
    }

    @Test
    fun resamples44kTo16kWithInterpolation() {
        val out = Resampler.to16k(tone(0.5, samples = 4410, rate = 44100), 44100)
        assertEquals(1600, out.size)
    }

    @Test
    fun sixteenKIsUnchanged() {
        val input = tone(0.3)
        assertTrue(Resampler.to16k(input, 16000) === input)
    }

    @Test
    fun vadDetectsSpeechAfterQuietAndHoldsThroughShortPauses() {
        val vad = VoiceActivityDetector(sensitivity = 0.6f, hangoverMs = 500)
        repeat(20) { assertFalse(vad.process(tone(0.0005), 100).speech) }   // quiet room
        assertTrue(vad.process(tone(0.3), 100).speech)                         // speech
        assertTrue("hangover keeps the gate open", vad.process(tone(0.0005), 100).speech)
        repeat(6) { vad.process(tone(0.0005), 100) }
        assertFalse("closes after the hangover", vad.process(tone(0.0005), 100).speech)
    }

    @Test
    fun vadIgnoresDigitalSilence() {
        val vad = VoiceActivityDetector()
        repeat(10) { assertFalse(vad.process(ShortArray(1600), 100).rawSpeech) }
    }

    @Test
    fun bufferDropsOldestWhenFullAndCounts() {
        val b = AudioBufferManager(capacity = 3)
        repeat(5) { b.offer(AudioChunk(ShortArray(1600) { _ -> it.toShort() }, captureTimeMs = it.toLong())) }
        assertEquals(3, b.size)
        assertEquals(2L, b.droppedCount)
        assertEquals("oldest two were dropped", 2L, b.poll(10)!!.captureTimeMs)
    }

    @Test
    fun bufferDropsStaleAudioAfterReconnect() {
        val b = AudioBufferManager(capacity = 10)
        for (t in listOf(0L, 500L, 1_000L, 2_900L)) b.offer(AudioChunk(ShortArray(1600), t))
        val dropped = b.dropOlderThan(nowMs = 3_000, maxAgeMs = 1_500)
        assertEquals(3, dropped)
        assertEquals(2_900L, b.poll(10)!!.captureTimeMs)
    }

    @Test
    fun preRollKeepsOnlyRecentAudio() {
        val p = PreRollBuffer(maxMs = 300)
        repeat(6) { p.add(AudioChunk(ShortArray(1600), it.toLong())) }
        val drained = p.drain()
        assertEquals(3, drained.size)
        assertEquals(3L, drained.first().captureTimeMs)
        assertTrue(p.drain().isEmpty())
    }

    @Test
    fun chunkSerialisesLittleEndian() {
        val bytes = AudioChunk(shortArrayOf(0x0102, -2), 0).toLittleEndianBytes()
        assertEquals(listOf<Byte>(0x02, 0x01, 0xFE.toByte(), 0xFF.toByte()), bytes.toList())
        assertEquals(100, AudioChunk(ShortArray(1600), 0).durationMs)
    }
}
