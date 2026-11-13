package app.livelingo.speech

import app.livelingo.audio.AudioChunk
import app.livelingo.core.ErrorKind

/**
 * Engine-neutral transcription token. Cloud engines send many small tokens; the on-device engine
 * sends whole phrases. The caption assembler treats both the same way.
 */
data class SpeechToken(
    val text: String,
    val isFinal: Boolean,
    val kind: Kind,
    /** Language of [text]. For translation tokens this is the target language. */
    val language: String? = null,
) {
    enum class Kind {
        /** Words as spoken. */
        ORIGINAL,
        /** Translated words. */
        TRANSLATION,
        /** Spoken words already in the target language: both original and translation. */
        SAME_LANGUAGE,
        /** End of an utterance (pause detected or finalisation requested). */
        END_OF_SEGMENT,
    }
}

sealed interface SpeechEvent {
    data object Connected : SpeechEvent

    /**
     * One update. Final tokens are delivered once. Non-final tokens replace the previous
     * non-final tokens of the same stream.
     */
    data class Tokens(
        val finalTokens: List<SpeechToken>,
        val nonFinalTokens: List<SpeechToken>,
        /** Audio (ms, on the engine's clock) fully processed into tokens so far. */
        val processedAudioMs: Long? = null,
    ) : SpeechEvent

    data class Failure(
        val kind: ErrorKind,
        val message: String,
        val retryable: Boolean,
        val retryAfterMs: Long = 0,
    ) : SpeechEvent

    data object Finished : SpeechEvent
}

data class RecognitionConfig(
    val sampleRate: Int,
    /** null means automatic language detection. */
    val sourceLanguage: String?,
    val targetLanguage: String,
    /** When true the engine translates inline; otherwise the TranslationManager does it. */
    val translateInline: Boolean,
    val dialectLocale: String? = null,
    val endpointDelayMs: Int = 800,
    val model: String = "stt-rt-v6",
)

data class EngineCapabilities(
    val streaming: Boolean,
    val automaticLanguageId: Boolean,
    val inlineTranslation: Boolean,
    val needsNetwork: Boolean,
)

/** Contract every speech recognition engine implements. */
interface SpeechRecognitionProvider {
    val id: String
    val displayName: String
    val capabilities: EngineCapabilities

    /** Opens a session. Events arrive on [listener] from any thread. */
    suspend fun start(config: RecognitionConfig, listener: (SpeechEvent) -> Unit)

    /** Non-blocking. Returns false if the engine cannot accept audio right now. */
    fun sendAudio(chunk: AudioChunk): Boolean

    /** Asks the engine to finalise everything heard so far (used after a long pause). */
    fun finalizeNow()

    /** Keeps an idle connection open while nothing is being said. */
    fun keepAlive()

    /** Bytes queued in the transport but not yet sent. Used to detect a stalled network. */
    fun pendingBytes(): Long = 0

    suspend fun stop()
}
