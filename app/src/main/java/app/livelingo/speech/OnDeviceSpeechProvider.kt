package app.livelingo.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import app.livelingo.audio.AudioChunk
import app.livelingo.audio.PIPELINE_SAMPLE_RATE
import app.livelingo.core.ErrorKind
import app.livelingo.core.Languages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Experimental offline engine (Android 13+). Feeds the captured PCM stream into Android's
 * on-device recognizer through a pipe (EXTRA_AUDIO_SOURCE) in a segmented session, so it keeps
 * listening across pauses. It needs a fixed source language and its offline model installed.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class OnDeviceSpeechProvider(private val context: Context) : SpeechRecognitionProvider {

    override val id = "on_device"
    override val displayName = "On-device recognizer"
    override val capabilities = EngineCapabilities(
        streaming = true, automaticLanguageId = false, inlineTranslation = false, needsNetwork = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "LiveLingo-ondevice-pipe") }
    private val queuedWrites = AtomicInteger(0)

    @Volatile private var running = false
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var output: OutputStream? = null
    private var readSide: ParcelFileDescriptor? = null
    private var config: RecognitionConfig? = null
    private var listener: ((SpeechEvent) -> Unit)? = null

    override suspend fun start(config: RecognitionConfig, listener: (SpeechEvent) -> Unit) = withContext(Dispatchers.Main) {
        this@OnDeviceSpeechProvider.config = config
        this@OnDeviceSpeechProvider.listener = listener
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            listener(SpeechEvent.Failure(ErrorKind.SPEECH_RECOGNITION_FAILED, "This phone does not offer on-device speech recognition. Use the Soniox engine.", retryable = false))
            return@withContext
        }
        if (config.sourceLanguage == null) {
            listener(SpeechEvent.Failure(ErrorKind.LANGUAGE_DETECTION_FAILED, "The on-device engine cannot detect the language. Turn off auto-detect and choose the source language.", retryable = false))
            return@withContext
        }
        running = true
        startSession()
    }

    private fun locale(): String {
        val c = config ?: return "en-US"
        return c.dialectLocale?.takeIf { it.startsWith(c.sourceLanguage ?: "") }
            ?: Languages.find(c.sourceLanguage)?.onDeviceLocale ?: "en-US"
    }

    private fun startSession() {
        if (!running) return
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) {
            listener?.invoke(SpeechEvent.Failure(ErrorKind.SPEECH_RECOGNITION_FAILED, "Could not open the audio pipe.", retryable = true, retryAfterMs = 1_000))
            return
        }
        readSide = pipe[0]
        output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PIPELINE_SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = rec
        rec.setRecognitionListener(Listener())
        rec.startListening(intent)
        listener?.invoke(SpeechEvent.Connected)
    }

    private fun lang(): String? = config?.sourceLanguage

    private fun firstResult(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults) ?: return
            listener?.invoke(SpeechEvent.Tokens(emptyList(), listOf(SpeechToken(text, false, SpeechToken.Kind.ORIGINAL, lang()))))
        }

        override fun onSegmentResults(segmentResults: Bundle) = deliverFinal(segmentResults)

        override fun onResults(results: Bundle?) {
            deliverFinal(results)
            restartSoon(150)
        }

        override fun onEndOfSegmentedSession() = restartSoon(150)

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_CLIENT -> restartSoon(250)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartSoon(800)
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    running = false
                    listener?.invoke(SpeechEvent.Failure(ErrorKind.SPEECH_RECOGNITION_FAILED, "The offline speech model for ${Languages.nameOf(lang())} is not installed. Download it in Engine settings.", retryable = false))
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    running = false
                    listener?.invoke(SpeechEvent.Failure(ErrorKind.MICROPHONE_PERMISSION_DENIED, "Speech recognition permission was denied.", retryable = false))
                }
                else -> listener?.invoke(SpeechEvent.Failure(ErrorKind.SPEECH_RECOGNITION_FAILED, "On-device recognizer error $error.", retryable = true, retryAfterMs = 1_000))
            }
        }
    }

    private fun deliverFinal(b: Bundle?) {
        val text = firstResult(b) ?: return
        listener?.invoke(
            SpeechEvent.Tokens(
                finalTokens = listOf(
                    SpeechToken(text, true, SpeechToken.Kind.ORIGINAL, lang()),
                    SpeechToken("", true, SpeechToken.Kind.END_OF_SEGMENT, lang()),
                ),
                nonFinalTokens = emptyList(),
            )
        )
    }

    private fun restartSoon(delayMs: Long) {
        main.post { teardownSession() }
        main.postDelayed({ if (running) startSession() }, delayMs)
    }

    private fun teardownSession() {
        runCatching { output?.close() }
        output = null
        runCatching { readSide?.close() }
        readSide = null
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    override fun sendAudio(chunk: AudioChunk): Boolean {
        if (!running) return false
        if (queuedWrites.get() > 20) return false
        val bytes = chunk.toLittleEndianBytes()
        queuedWrites.incrementAndGet()
        writer.execute {
            try { output?.write(bytes) } catch (_: IOException) { /* session restarting */ }
            queuedWrites.decrementAndGet()
        }
        return true
    }

    override fun finalizeNow() = Unit
    override fun keepAlive() = Unit
    override fun pendingBytes(): Long = queuedWrites.get().toLong() * 3_200

    override suspend fun stop() {
        running = false
        withContext(Dispatchers.Main) {
            main.removeCallbacksAndMessages(null)
            runCatching { recognizer?.cancel() }
            teardownSession()
        }
    }

    companion object {
        fun isSupportedOnThisPhone(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /** Reports whether the offline model for [locale] is installed. Must be called on the main thread. */
        suspend fun checkModel(context: Context, locale: String): String = withContext(Dispatchers.Main) {
            if (!isSupportedOnThisPhone(context)) return@withContext "On-device recognition is not available on this phone."
            val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            val result = suspendCancellableCoroutine { cont ->
                rec.checkRecognitionSupport(intent, Executors.newSingleThreadExecutor(), object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val base = locale.substringBefore('-')
                        val installed = support.installedOnDeviceLanguages.any { it.startsWith(base) }
                        val pending = support.pendingOnDeviceLanguages.any { it.startsWith(base) }
                        val available = support.supportedOnDeviceLanguages.any { it.startsWith(base) }
                        val msg = when {
                            installed -> "Offline model for $locale is installed."
                            pending -> "Offline model for $locale is downloading."
                            available -> "Offline model for $locale can be downloaded."
                            else -> "$locale is not supported offline on this phone."
                        }
                        if (cont.isActive) cont.resume(msg)
                    }

                    override fun onError(error: Int) {
                        if (cont.isActive) cont.resume("Could not check offline support (error $error).")
                    }
                })
            }
            rec.destroy()
            result
        }

        /** Asks the system to download the offline model for [locale]. Main thread only. */
        fun downloadModel(context: Context, locale: String) {
            if (!isSupportedOnThisPhone(context)) return
            val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            rec.triggerModelDownload(intent)
            Handler(Looper.getMainLooper()).postDelayed({ rec.destroy() }, 5_000)
        }
    }
}
