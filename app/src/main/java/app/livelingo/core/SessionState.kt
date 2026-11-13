package app.livelingo.core

/** States shown on the Quick Settings tile, the notification and the dashboard. */
enum class TranslationState { OFF, STARTING, TRANSLATING, PAUSED, ERROR }

enum class AudioSourceType { INTERNAL, MICROPHONE }

/** What the capture engine is honestly doing right now. */
enum class CaptureStatus(val label: String) {
    IDLE("Not capturing"),
    INTERNAL_ACTIVE("Internal audio"),
    MICROPHONE_ACTIVE("Microphone"),
    WAITING_FOR_AUDIO("Waiting for audio"),
    UNAVAILABLE("Capture unavailable"),
}

/** The error situations the app recognises and explains. */
enum class ErrorKind {
    OVERLAY_PERMISSION_DENIED,
    CAPTURE_PERMISSION_DENIED,
    INTERNAL_CAPTURE_BLOCKED,
    MICROPHONE_PERMISSION_DENIED,
    NO_INTERNET,
    TRANSLATION_UNAVAILABLE,
    SPEECH_RECOGNITION_FAILED,
    LANGUAGE_DETECTION_FAILED,
    SERVICE_STOPPED,
    BATTERY_OPTIMISATION_KILL,
    AUDIO_STREAM_INTERRUPTED,
    RATE_LIMITED,
    NOT_CONFIGURED,
    AUTH_FAILED,
    OUT_OF_CREDIT,
}

/** What the user can do about an error. Shown as a button. */
enum class RecoveryAction(val label: String) {
    GRANT_OVERLAY("Allow overlay"),
    GRANT_MICROPHONE("Allow microphone"),
    RESTART("Start again"),
    SWITCH_TO_MICROPHONE("Use microphone"),
    OPEN_ENGINE_SETTINGS("Engine settings"),
    OPEN_BATTERY_SETTINGS("Battery settings"),
    WAIT("Waiting…"),
}

data class AppError(
    val kind: ErrorKind,
    val message: String,
    val recovery: RecoveryAction? = null,
    /** Errors that clear themselves (network back, provider recovered) are transient. */
    val transient: Boolean = false,
)

data class SessionStatus(
    val state: TranslationState = TranslationState.OFF,
    val capture: CaptureStatus = CaptureStatus.IDLE,
    val sourceType: AudioSourceType? = null,
    val detectedLanguage: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String = "en",
    val engineLabel: String = "",
    val error: AppError? = null,
    val notice: String? = null,
    val subtitlesVisible: Boolean = true,
    val adjustingOverlay: Boolean = false,
) {
    val isActive get() = state == TranslationState.STARTING || state == TranslationState.TRANSLATING || state == TranslationState.PAUSED

    fun languageLine(): String {
        val src = detectedLanguage ?: sourceLanguage
        val srcLabel = if (src != null) Languages.nameOf(src) else "Auto-detect"
        return "$srcLabel → ${Languages.nameOf(targetLanguage)}"
    }
}

/** What the overlay draws. Final text is stable; provisional text may still change. */
data class CaptionDisplay(
    val originalFinal: String = "",
    val originalProvisional: String = "",
    val translationFinal: String = "",
    val translationProvisional: String = "",
    val originalLanguage: String? = null,
) {
    val isEmpty get() = originalFinal.isBlank() && originalProvisional.isBlank() &&
        translationFinal.isBlank() && translationProvisional.isBlank()
}

data class Diagnostics(
    val audioInput: String = "Idle",
    val sampleRate: Int = 0,
    val levelDb: Float = -90f,
    val speechDetected: Boolean = false,
    val detectedLanguage: String? = null,
    val recognitionLatencyMs: Long? = null,
    val translationLatencyMs: Long? = null,
    val totalLatencyMs: Long? = null,
    val networkOnline: Boolean = true,
    val connection: String = "Disconnected",
    val bufferQueued: Int = 0,
    val bufferCapacity: Int = 0,
    val droppedChunks: Long = 0,
    val audioSentSeconds: Double = 0.0,
    val bytesSent: Long = 0,
    val reconnects: Int = 0,
    val sessionStartedAt: Long = 0,
    val batteryAtStart: Int = -1,
    val batteryNow: Int = -1,
)
