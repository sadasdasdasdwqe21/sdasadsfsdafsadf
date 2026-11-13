package app.livelingo.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.livelingo.core.AudioSourceType

class CaptureException(message: String) : Exception(message)

sealed interface CaptureEvent {
    /** Internal capture is producing digital silence while media is playing. */
    data class Blocked(val reason: String) : CaptureEvent
    /** Audio is flowing again after a Blocked event. */
    data object Unblocked : CaptureEvent
    data class Failed(val reason: String) : CaptureEvent
}

/** Common contract for every audio source. Output is always 16 kHz mono PCM16 chunks. */
interface AudioCaptureProvider {
    val type: AudioSourceType
    val description: String
    val inputSampleRate: Int
    fun start(sink: (AudioChunk) -> Unit, events: (CaptureEvent) -> Unit)
    fun pause()
    fun resume()
    fun stop()
}

/** Shared AudioRecord reading loop on a high-priority audio thread. */
abstract class AudioRecordCapture(protected val context: Context) : AudioCaptureProvider {

    @Volatile private var running = false
    @Volatile private var paused = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    final override var inputSampleRate: Int = PIPELINE_SAMPLE_RATE
        private set

    protected abstract fun createRecord(sampleRate: Int): AudioRecord?
    protected open fun onRecordCreated(record: AudioRecord) {}
    protected open fun onChunk(chunk: AudioChunk, events: (CaptureEvent) -> Unit) {}
    protected open fun onReleased() {}

    override fun start(sink: (AudioChunk) -> Unit, events: (CaptureEvent) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw CaptureException("Microphone/audio permission has not been granted.")
        }
        var rec: AudioRecord? = null
        for (rate in intArrayOf(PIPELINE_SAMPLE_RATE, 48_000, 44_100)) {
            rec = runCatching { createRecord(rate) }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                inputSampleRate = rate
                break
            }
            rec?.release()
            rec = null
        }
        val r = rec ?: throw CaptureException("This device refused to open the ${description.lowercase()} stream.")
        onRecordCreated(r)
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            throw CaptureException("Could not start the ${description.lowercase()} stream. Another app may be using it.")
        }
        record = r
        running = true
        paused = false
        thread = Thread({ loop(r, sink, events) }, "LiveLingo-${type.name.lowercase()}").also { it.start() }
    }

    private fun loop(r: AudioRecord, sink: (AudioChunk) -> Unit, events: (CaptureEvent) -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ShortArray(inputSampleRate * CHUNK_MS / 1000)
        var filled = 0
        while (running) {
            if (paused) {
                filled = 0
                SystemClock.sleep(40)
                continue
            }
            val n = r.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
            when {
                n > 0 -> {
                    filled += n
                    if (filled == frame.size) {
                        val chunk = AudioChunk(Resampler.to16k(frame.copyOf(), inputSampleRate), SystemClock.elapsedRealtime())
                        filled = 0
                        onChunk(chunk, events)
                        sink(chunk)
                    }
                }
                n == AudioRecord.ERROR_DEAD_OBJECT -> {
                    if (running && !paused) events(CaptureEvent.Failed("The audio stream was interrupted by the system."))
                    running = false
                }
                n < 0 -> {
                    if (running && !paused) {
                        events(CaptureEvent.Failed("Audio read error ($n)."))
                        running = false
                    }
                }
                else -> SystemClock.sleep(5)
            }
        }
    }

    override fun pause() {
        paused = true
        runCatching { record?.stop() }
    }

    override fun resume() {
        val r = record ?: return
        runCatching { r.startRecording() }
        paused = false
    }

    override fun stop() {
        running = false
        runCatching { record?.stop() }
        thread?.join(500)
        thread = null
        runCatching { record?.release() }
        record = null
        onReleased()
    }

    protected fun format(rate: Int): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(rate)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    protected fun bufferSize(rate: Int): Int {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return maxOf(min * 2, rate / 2 * 2) // at least 500 ms of headroom
    }
}

/**
 * Captures what other apps are playing (Android 10+), using a MediaProjection token the user
 * approved. Only media, game and unknown usages are capturable, and apps can opt out; when an
 * app opts out Android delivers silence, which [onChunk] detects and reports honestly.
 */
class InternalAudioCapture(
    context: Context,
    private val projection: MediaProjection,
) : AudioRecordCapture(context) {

    override val type = AudioSourceType.INTERNAL
    override val description = "Internal audio"

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var silentMs = 0
    private var sinceCheckMs = 0
    private var blockedReported = false

    @SuppressLint("MissingPermission")
    override fun createRecord(sampleRate: Int): AudioRecord? {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(format(sampleRate))
            .setBufferSizeInBytes(bufferSize(sampleRate))
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    override fun onChunk(chunk: AudioChunk, events: (CaptureEvent) -> Unit) {
        if (AudioMath.isDigitalSilence(chunk.samples)) {
            silentMs += chunk.durationMs
        } else {
            silentMs = 0
            if (blockedReported) {
                blockedReported = false
                events(CaptureEvent.Unblocked)
            }
        }
        sinceCheckMs += chunk.durationMs
        if (sinceCheckMs >= 1000) {
            sinceCheckMs = 0
            if (!blockedReported && silentMs >= 4000 && audioManager?.isMusicActive == true) {
                blockedReported = true
                events(CaptureEvent.Blocked(blockReason()))
            }
        }
    }

    /** Explains, as precisely as Android allows, why playing audio is not reaching us. */
    private fun blockReason(): String {
        val configs = runCatching { audioManager?.activePlaybackConfigurations.orEmpty() }.getOrDefault(emptyList())
        val restricted = configs.any { it.audioAttributes.allowedCapturePolicy != AudioAttributes.ALLOW_CAPTURE_BY_ALL }
        val callAudio = configs.any {
            it.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION ||
                it.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
        }
        return when {
            restricted -> "The app that is playing has blocked other apps from capturing its audio."
            callAudio -> "Call audio cannot be captured by other apps on Android."
            else -> "Audio is playing but none of it can be captured. The app may restrict capture."
        }
    }
}

/**
 * Microphone fallback. Acoustic echo cancellation is deliberately NOT enabled: it removes the
 * phone's own speaker output from the microphone signal, which is exactly the video audio we
 * need to hear. Noise suppression and automatic gain are optional.
 */
class MicrophoneCapture(
    context: Context,
    private val noiseSuppression: Boolean,
    private val autoGain: Boolean,
) : AudioRecordCapture(context) {

    override val type = AudioSourceType.MICROPHONE
    override val description = "Microphone"

    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @SuppressLint("MissingPermission")
    override fun createRecord(sampleRate: Int): AudioRecord? =
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(format(sampleRate))
            .setBufferSizeInBytes(bufferSize(sampleRate))
            .build()

    override fun onRecordCreated(record: AudioRecord) {
        if (noiseSuppression && NoiseSuppressor.isAvailable()) {
            ns = runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        if (autoGain && AutomaticGainControl.isAvailable()) {
            agc = runCatching { AutomaticGainControl.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
    }

    override fun onReleased() {
        runCatching { ns?.release() }
        runCatching { agc?.release() }
        ns = null
        agc = null
    }
}

/** Facts about the audio setup, used for warnings and the audio settings screen. */
object AudioEnvironment {
    fun internalCaptureSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun headphonesConnected(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val types = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }
}
