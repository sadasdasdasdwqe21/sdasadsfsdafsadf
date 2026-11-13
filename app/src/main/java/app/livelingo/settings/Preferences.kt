package app.livelingo.settings

import app.livelingo.core.AudioSourceType
import app.livelingo.core.Languages
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------------------------
// Preference groups. Each group is stored as one JSON value in DataStore, so adding a field is
// a one-line change with a default, and old installs keep working.
// ---------------------------------------------------------------------------------------------

data class LanguagePreferences(
    val autoDetect: Boolean = true,
    val sourceLanguage: String = "ar",
    val targetLanguage: String = "en",
    /** Locale used for Arabic (and other dialect-aware languages) by the on-device engine. */
    val dialectLocale: String? = null,
    val recentPairs: List<String> = emptyList(),
    val favouritePairs: List<String> = listOf("auto>en", "ar>en"),
) {
    val effectiveSource: String? get() = if (autoDetect) null else sourceLanguage
    val pairKey: String get() = Languages.pairKey(effectiveSource, targetLanguage)

    fun withRecent(key: String): LanguagePreferences =
        copy(recentPairs = (listOf(key) + recentPairs.filter { it != key }).take(6))

    fun toggleFavourite(key: String): LanguagePreferences =
        copy(favouritePairs = if (key in favouritePairs) favouritePairs - key else favouritePairs + key)

    fun toJson(): String = JSONObject()
        .put("autoDetect", autoDetect)
        .put("source", sourceLanguage)
        .put("target", targetLanguage)
        .put("dialect", dialectLocale ?: "")
        .put("recent", JSONArray(recentPairs))
        .put("fav", JSONArray(favouritePairs))
        .toString()

    companion object {
        fun fromJson(s: String?): LanguagePreferences {
            if (s.isNullOrBlank()) return LanguagePreferences()
            return runCatching {
                val o = JSONObject(s)
                val d = LanguagePreferences()
                LanguagePreferences(
                    autoDetect = o.optBoolean("autoDetect", d.autoDetect),
                    sourceLanguage = o.optString("source", d.sourceLanguage).takeIf { Languages.isSupported(it) } ?: d.sourceLanguage,
                    targetLanguage = o.optString("target", d.targetLanguage).takeIf { Languages.isSupported(it) } ?: d.targetLanguage,
                    dialectLocale = o.optString("dialect", "").ifBlank { null },
                    recentPairs = o.optJSONArray("recent").toStringList(),
                    favouritePairs = o.optJSONArray("fav")?.toStringList() ?: d.favouritePairs,
                )
            }.getOrDefault(LanguagePreferences())
        }
    }
}

enum class SubtitlePreset(val label: String) {
    CINEMA("Cinema"),
    MINIMAL("Minimal"),
    YOUTUBE("YouTube style"),
    HIGH_CONTRAST("High contrast"),
    CUSTOM("Custom"),
}

enum class FontChoice(val label: String, val family: String, val boldDefault: Boolean = false) {
    SANS("Sans", "sans-serif"),
    SANS_MEDIUM("Sans Medium", "sans-serif-medium"),
    CONDENSED("Condensed", "sans-serif-condensed"),
    SERIF("Serif", "serif"),
    MONO("Mono", "monospace"),
}

enum class TextAlign(val label: String) { START("Left"), CENTER("Centre"), END("Right") }

enum class DisplayMode(val label: String) {
    TRANSLATION_ONLY("Translation only"),
    ORIGINAL_AND_TRANSLATION("Original + translation"),
}

enum class OverlayAnchor(val label: String, val yFraction: Float) {
    TOP("Top", 0.08f),
    MIDDLE("Middle", 0.42f),
    BOTTOM("Bottom", 0.74f),
}

data class SubtitlePreferences(
    val preset: SubtitlePreset = SubtitlePreset.CINEMA,
    val fontSizeSp: Float = 20f,
    val font: FontChoice = FontChoice.SANS_MEDIUM,
    val bold: Boolean = false,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val originalTextColor: Int = 0xFFFFE08A.toInt(),
    val backgroundColor: Int = 0xFF000000.toInt(),
    val backgroundOpacity: Float = 0.45f,
    val outline: Boolean = true,
    val outlineWidthDp: Float = 2f,
    val outlineColor: Int = 0xFF000000.toInt(),
    val shadow: Boolean = true,
    val cornerRadiusDp: Float = 12f,
    val lineSpacing: Float = 1.12f,
    val align: TextAlign = TextAlign.CENTER,
    val maxLines: Int = 3,
    val displayMode: DisplayMode = DisplayMode.TRANSLATION_ONLY,
    // Position and size, kept as screen fractions so they survive rotation.
    val xFraction: Float = 0.04f,
    val yFraction: Float = OverlayAnchor.BOTTOM.yFraction,
    val widthFraction: Float = 0.92f,
    val minHeightDp: Float = 0f,
    val locked: Boolean = true,
    /** Keep the window at 80% opacity when locked so Android lets taps reach the app below. */
    val tapThrough: Boolean = true,
    val clearAfterSeconds: Int = 6,
    val showBubble: Boolean = true,
    val bubbleXFraction: Float = 0.86f,
    val bubbleYFraction: Float = 0.30f,
) {
    fun applyPreset(p: SubtitlePreset): SubtitlePreferences = when (p) {
        SubtitlePreset.CINEMA -> copy(
            preset = p, fontSizeSp = 20f, font = FontChoice.SANS_MEDIUM, bold = false,
            textColor = 0xFFFFFFFF.toInt(), backgroundColor = 0xFF000000.toInt(), backgroundOpacity = 0.45f,
            outline = true, outlineWidthDp = 2f, outlineColor = 0xFF000000.toInt(), shadow = true,
            cornerRadiusDp = 12f, lineSpacing = 1.12f, align = TextAlign.CENTER, maxLines = 3,
        )
        SubtitlePreset.MINIMAL -> copy(
            preset = p, fontSizeSp = 19f, font = FontChoice.SANS_MEDIUM, bold = false,
            textColor = 0xFFFFFFFF.toInt(), backgroundOpacity = 0f, outline = true, outlineWidthDp = 2.6f,
            outlineColor = 0xFF000000.toInt(), shadow = true, cornerRadiusDp = 0f, align = TextAlign.CENTER, maxLines = 2,
        )
        SubtitlePreset.YOUTUBE -> copy(
            preset = p, fontSizeSp = 18f, font = FontChoice.SANS, bold = false,
            textColor = 0xFFFFFFFF.toInt(), backgroundColor = 0xFF080808.toInt(), backgroundOpacity = 0.75f,
            outline = false, shadow = false, cornerRadiusDp = 4f, lineSpacing = 1.05f, align = TextAlign.CENTER, maxLines = 3,
        )
        SubtitlePreset.HIGH_CONTRAST -> copy(
            preset = p, fontSizeSp = 22f, font = FontChoice.SANS_MEDIUM, bold = true,
            textColor = 0xFFFFEB3B.toInt(), backgroundColor = 0xFF000000.toInt(), backgroundOpacity = 0.92f,
            outline = false, shadow = false, cornerRadiusDp = 6f, lineSpacing = 1.1f, align = TextAlign.CENTER, maxLines = 3,
        )
        SubtitlePreset.CUSTOM -> copy(preset = p)
    }

    fun toJson(): String = JSONObject()
        .put("preset", preset.name).put("fontSize", fontSizeSp.toDouble()).put("font", font.name).put("bold", bold)
        .put("textColor", textColor.toLong()).put("origColor", originalTextColor.toLong())
        .put("bgColor", backgroundColor.toLong()).put("bgOpacity", backgroundOpacity.toDouble())
        .put("outline", outline).put("outlineW", outlineWidthDp.toDouble()).put("outlineColor", outlineColor.toLong())
        .put("shadow", shadow).put("corner", cornerRadiusDp.toDouble()).put("lineSpacing", lineSpacing.toDouble())
        .put("align", align.name).put("maxLines", maxLines).put("mode", displayMode.name)
        .put("x", xFraction.toDouble()).put("y", yFraction.toDouble()).put("w", widthFraction.toDouble())
        .put("minH", minHeightDp.toDouble()).put("locked", locked).put("tapThrough", tapThrough)
        .put("clearAfter", clearAfterSeconds).put("bubble", showBubble)
        .put("bx", bubbleXFraction.toDouble()).put("by", bubbleYFraction.toDouble())
        .toString()

    companion object {
        fun fromJson(s: String?): SubtitlePreferences {
            if (s.isNullOrBlank()) return SubtitlePreferences()
            return runCatching {
                val o = JSONObject(s)
                val d = SubtitlePreferences()
                SubtitlePreferences(
                    preset = enumOr(o.optString("preset"), d.preset),
                    fontSizeSp = o.optDouble("fontSize", d.fontSizeSp.toDouble()).toFloat().coerceIn(12f, 40f),
                    font = enumOr(o.optString("font"), d.font),
                    bold = o.optBoolean("bold", d.bold),
                    textColor = o.optLong("textColor", d.textColor.toLong()).toInt(),
                    originalTextColor = o.optLong("origColor", d.originalTextColor.toLong()).toInt(),
                    backgroundColor = o.optLong("bgColor", d.backgroundColor.toLong()).toInt(),
                    backgroundOpacity = o.optDouble("bgOpacity", d.backgroundOpacity.toDouble()).toFloat().coerceIn(0f, 1f),
                    outline = o.optBoolean("outline", d.outline),
                    outlineWidthDp = o.optDouble("outlineW", d.outlineWidthDp.toDouble()).toFloat().coerceIn(0.5f, 6f),
                    outlineColor = o.optLong("outlineColor", d.outlineColor.toLong()).toInt(),
                    shadow = o.optBoolean("shadow", d.shadow),
                    cornerRadiusDp = o.optDouble("corner", d.cornerRadiusDp.toDouble()).toFloat().coerceIn(0f, 32f),
                    lineSpacing = o.optDouble("lineSpacing", d.lineSpacing.toDouble()).toFloat().coerceIn(0.8f, 2f),
                    align = enumOr(o.optString("align"), d.align),
                    maxLines = o.optInt("maxLines", d.maxLines).coerceIn(1, 6),
                    displayMode = enumOr(o.optString("mode"), d.displayMode),
                    xFraction = o.optDouble("x", d.xFraction.toDouble()).toFloat().coerceIn(0f, 0.95f),
                    yFraction = o.optDouble("y", d.yFraction.toDouble()).toFloat().coerceIn(0f, 0.95f),
                    widthFraction = o.optDouble("w", d.widthFraction.toDouble()).toFloat().coerceIn(0.3f, 1f),
                    minHeightDp = o.optDouble("minH", d.minHeightDp.toDouble()).toFloat().coerceIn(0f, 400f),
                    locked = o.optBoolean("locked", d.locked),
                    tapThrough = o.optBoolean("tapThrough", d.tapThrough),
                    clearAfterSeconds = o.optInt("clearAfter", d.clearAfterSeconds).coerceIn(2, 30),
                    showBubble = o.optBoolean("bubble", d.showBubble),
                    bubbleXFraction = o.optDouble("bx", d.bubbleXFraction.toDouble()).toFloat().coerceIn(0f, 1f),
                    bubbleYFraction = o.optDouble("by", d.bubbleYFraction.toDouble()).toFloat().coerceIn(0f, 1f),
                )
            }.getOrDefault(SubtitlePreferences())
        }
    }
}

data class AudioPreferences(
    val preferredSource: AudioSourceType = AudioSourceType.INTERNAL,
    val autoFallbackToMicrophone: Boolean = true,
    val noiseSuppression: Boolean = true,
    val autoGain: Boolean = true,
    /** Skip sending silence to the cloud engine (saves data and cost). */
    val silenceGating: Boolean = true,
    /** 0 = least sensitive, 1 = most sensitive. */
    val vadSensitivity: Float = 0.6f,
) {
    fun toJson(): String = JSONObject()
        .put("source", preferredSource.name).put("fallback", autoFallbackToMicrophone)
        .put("ns", noiseSuppression).put("agc", autoGain).put("gating", silenceGating)
        .put("vad", vadSensitivity.toDouble()).toString()

    companion object {
        fun fromJson(s: String?): AudioPreferences {
            if (s.isNullOrBlank()) return AudioPreferences()
            return runCatching {
                val o = JSONObject(s)
                val d = AudioPreferences()
                AudioPreferences(
                    preferredSource = enumOr(o.optString("source"), d.preferredSource),
                    autoFallbackToMicrophone = o.optBoolean("fallback", d.autoFallbackToMicrophone),
                    noiseSuppression = o.optBoolean("ns", d.noiseSuppression),
                    autoGain = o.optBoolean("agc", d.autoGain),
                    silenceGating = o.optBoolean("gating", d.silenceGating),
                    vadSensitivity = o.optDouble("vad", d.vadSensitivity.toDouble()).toFloat().coerceIn(0f, 1f),
                )
            }.getOrDefault(AudioPreferences())
        }
    }
}

enum class SpeechEngine(val label: String, val description: String) {
    SONIOX("Soniox cloud (recommended)", "Streaming recognition, automatic language detection and translation in one connection. Needs internet and a Soniox account."),
    ON_DEVICE("On-device (experimental)", "Android's offline recognizer on Android 13+. Free and private, but needs a fixed source language and its offline model."),
}

enum class TranslationEngine(val label: String, val description: String) {
    INLINE("Same as speech engine", "Soniox translates as it transcribes, using the full sentence context. Fastest."),
    ML_KIT("On-device (ML Kit)", "Google ML Kit offline translation. Free after a one-time model download, less fluent with slang."),
}

enum class CredentialMode(val label: String) {
    TOKEN_SERVER("Token server (recommended)"),
    DEVICE_KEY("API key stored on this phone"),
}

data class EnginePreferences(
    val speechEngine: SpeechEngine = SpeechEngine.SONIOX,
    val translationEngine: TranslationEngine = TranslationEngine.INLINE,
    val credentialMode: CredentialMode = CredentialMode.DEVICE_KEY,
    val tokenServerUrl: String = "",
    val sonioxModel: String = "stt-rt-v6",
    /** How long a pause must be before a sentence is closed (Soniox accepts 500 to 3000 ms). */
    val endpointDelayMs: Int = 800,
) {
    fun toJson(): String = JSONObject()
        .put("speech", speechEngine.name).put("translation", translationEngine.name)
        .put("cred", credentialMode.name).put("tokenUrl", tokenServerUrl)
        .put("model", sonioxModel).put("endpointMs", endpointDelayMs).toString()

    companion object {
        fun fromJson(s: String?): EnginePreferences {
            if (s.isNullOrBlank()) return EnginePreferences()
            return runCatching {
                val o = JSONObject(s)
                val d = EnginePreferences()
                EnginePreferences(
                    speechEngine = enumOr(o.optString("speech"), d.speechEngine),
                    translationEngine = enumOr(o.optString("translation"), d.translationEngine),
                    credentialMode = enumOr(o.optString("cred"), d.credentialMode),
                    tokenServerUrl = o.optString("tokenUrl", d.tokenServerUrl),
                    sonioxModel = o.optString("model", d.sonioxModel).ifBlank { d.sonioxModel },
                    endpointDelayMs = o.optInt("endpointMs", d.endpointDelayMs).coerceIn(500, 3000),
                )
            }.getOrDefault(EnginePreferences())
        }
    }
}

data class GeneralPreferences(
    val historyEnabled: Boolean = true,
    val onboardingDone: Boolean = false,
) {
    fun toJson(): String = JSONObject().put("history", historyEnabled).put("onboarding", onboardingDone).toString()

    companion object {
        fun fromJson(s: String?): GeneralPreferences {
            if (s.isNullOrBlank()) return GeneralPreferences()
            return runCatching {
                val o = JSONObject(s)
                GeneralPreferences(o.optBoolean("history", true), o.optBoolean("onboarding", false))
            }.getOrDefault(GeneralPreferences())
        }
    }
}

data class AppSettings(
    val language: LanguagePreferences = LanguagePreferences(),
    val subtitle: SubtitlePreferences = SubtitlePreferences(),
    val audio: AudioPreferences = AudioPreferences(),
    val engine: EnginePreferences = EnginePreferences(),
    val general: GeneralPreferences = GeneralPreferences(),
)

internal inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    runCatching { enumValueOf<E>(name ?: "") }.getOrDefault(default)

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
}
