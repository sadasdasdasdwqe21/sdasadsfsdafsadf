package app.livelingo.speech

import app.livelingo.core.ErrorKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure (Android-free) encoding and decoding of the Soniox real-time WebSocket protocol.
 * Kept separate from the transport so it can be unit tested.
 */
object SonioxProtocol {

    const val ENDPOINT = "wss://stt-rt.soniox.com/transcribe-websocket"
    const val TEMP_KEY_ENDPOINT = "https://api.soniox.com/v1/auth/temporary-api-key"

    const val KEEPALIVE = "{\"type\":\"keepalive\"}"
    const val FINALIZE = "{\"type\":\"finalize\"}"
    /** An empty text frame asks the server to finish the stream gracefully. */
    const val END_OF_AUDIO = ""

    fun buildConfig(config: RecognitionConfig): String {
        val o = JSONObject()
            .put("model", config.model)
            .put("audio_format", "pcm_s16le")
            .put("sample_rate", config.sampleRate)
            .put("num_channels", 1)
            .put("enable_language_identification", true)
            .put("enable_endpoint_detection", true)
            .put("max_endpoint_delay_ms", config.endpointDelayMs.coerceIn(500, 3000))
        if (config.sourceLanguage != null) {
            o.put("language_hints", JSONArray(listOf(config.sourceLanguage)))
        }
        if (config.translateInline) {
            o.put(
                "translation",
                JSONObject().put("type", "one_way").put("target_language", config.targetLanguage),
            )
        }
        return o.toString()
    }

    data class Message(
        val finalTokens: List<SpeechToken>,
        val nonFinalTokens: List<SpeechToken>,
        val finalAudioMs: Long?,
        val totalAudioMs: Long?,
        val finished: Boolean,
        val error: SpeechEvent.Failure?,
    )

    fun parse(text: String): Message {
        val o = JSONObject(text)
        if (o.has("error_code") || o.has("error_type") || o.has("error_message")) {
            return Message(emptyList(), emptyList(), null, null, false, errorFrom(o))
        }
        val finals = ArrayList<SpeechToken>()
        val nonFinals = ArrayList<SpeechToken>()
        val arr = o.optJSONArray("tokens")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val token = toToken(t) ?: continue
                if (token.isFinal) finals += token else nonFinals += token
            }
        }
        return Message(
            finalTokens = finals,
            nonFinalTokens = nonFinals,
            finalAudioMs = if (o.has("final_audio_proc_ms")) o.optLong("final_audio_proc_ms") else null,
            totalAudioMs = if (o.has("total_audio_proc_ms")) o.optLong("total_audio_proc_ms") else null,
            finished = o.optBoolean("finished", false),
            error = null,
        )
    }

    private fun toToken(t: JSONObject): SpeechToken? {
        val text = t.optString("text", "")
        if (text.isEmpty()) return null
        val isFinal = t.optBoolean("is_final", false)
        val language = t.optString("language", "").ifBlank { null }
        // Special control tokens such as <end> (endpoint) and <fin> (manual finalisation).
        if (text.length > 2 && text.startsWith("<") && text.endsWith(">") && !text.contains(' ')) {
            return SpeechToken("", true, SpeechToken.Kind.END_OF_SEGMENT, language)
        }
        val kind = when (t.optString("translation_status", "")) {
            "translation" -> SpeechToken.Kind.TRANSLATION
            "none" -> SpeechToken.Kind.SAME_LANGUAGE
            else -> SpeechToken.Kind.ORIGINAL
        }
        return SpeechToken(text, isFinal, kind, language)
    }

    /** Maps Soniox error codes onto the app's error kinds and retry policy. */
    fun errorFrom(o: JSONObject): SpeechEvent.Failure {
        val code = o.optInt("error_code", 0)
        val msg = o.optString("error_message", "").ifBlank { o.optString("error_type", "Unknown error") }
        return errorForCode(code, msg)
    }

    fun errorForCode(code: Int, msg: String): SpeechEvent.Failure = when (code) {
        400 -> SpeechEvent.Failure(ErrorKind.SPEECH_RECOGNITION_FAILED, "The speech engine rejected the settings: $msg", retryable = false)
        401 -> SpeechEvent.Failure(ErrorKind.AUTH_FAILED, "The Soniox key was rejected. Check it in Engine settings.", retryable = false)
        402 -> SpeechEvent.Failure(ErrorKind.OUT_OF_CREDIT, "The Soniox account has run out of credit or hit its budget.", retryable = false)
        403 -> SpeechEvent.Failure(ErrorKind.AUTH_FAILED, "The key has no real-time permission, or a temporary key expired.", retryable = true, retryAfterMs = 1_000)
        408, 500, 503 -> SpeechEvent.Failure(ErrorKind.TRANSLATION_UNAVAILABLE, "The speech service is temporarily unavailable ($code).", retryable = true, retryAfterMs = 2_000)
        413 -> SpeechEvent.Failure(ErrorKind.SPEECH_RECOGNITION_FAILED, "Session length limit reached, reconnecting.", retryable = true, retryAfterMs = 0)
        429 -> SpeechEvent.Failure(ErrorKind.RATE_LIMITED, "Rate limit reached. Retrying shortly.", retryable = true, retryAfterMs = 10_000)
        else -> SpeechEvent.Failure(ErrorKind.SPEECH_RECOGNITION_FAILED, "Speech engine error: $msg", retryable = true, retryAfterMs = 3_000)
    }
}
