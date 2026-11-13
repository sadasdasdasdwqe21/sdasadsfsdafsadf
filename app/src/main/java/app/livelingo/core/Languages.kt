package app.livelingo.core

/**
 * One supported language.
 *
 * [code] is the ISO 639-1 code used by the cloud speech engine (Soniox) and as the app's key.
 * [onDeviceLocale] is the BCP-47 tag handed to Android's on-device recognizer.
 * [dialects] lists regional variants, used where an engine needs a locale (on-device mode).
 */
data class Language(
    val code: String,
    val name: String,
    val nativeName: String,
    val onDeviceLocale: String,
    val rtl: Boolean = false,
    val dialects: List<Dialect> = emptyList(),
)

data class Dialect(val locale: String, val label: String)

object Languages {

    val arabicDialects = listOf(
        Dialect("ar-SA", "Modern Standard / Gulf (Saudi)"),
        Dialect("ar-EG", "Egyptian"),
        Dialect("ar-AE", "Gulf (Emirati)"),
        Dialect("ar-JO", "Levantine (Jordan)"),
        Dialect("ar-LB", "Levantine (Lebanon)"),
        Dialect("ar-IQ", "Iraqi"),
        Dialect("ar-MA", "Maghrebi (Morocco)"),
        Dialect("ar-DZ", "Maghrebi (Algeria)"),
    )

    /**
     * Registry of every language LiveLingo offers. Adding a language is one line here:
     * the pickers, engines and validation all read from this list.
     */
    val all: List<Language> = listOf(
        Language("ar", "Arabic", "العربية", "ar-SA", rtl = true, dialects = arabicDialects),
        Language("en", "English", "English", "en-US"),
        Language("es", "Spanish", "Español", "es-ES"),
        Language("fr", "French", "Français", "fr-FR"),
        Language("pt", "Portuguese", "Português", "pt-BR"),
        Language("de", "German", "Deutsch", "de-DE"),
        Language("it", "Italian", "Italiano", "it-IT"),
        Language("zh", "Chinese (Mandarin)", "中文", "zh-CN"),
        Language("ja", "Japanese", "日本語", "ja-JP"),
        Language("ko", "Korean", "한국어", "ko-KR"),
        Language("hi", "Hindi", "हिन्दी", "hi-IN"),
        Language("tr", "Turkish", "Türkçe", "tr-TR"),
        Language("ru", "Russian", "Русский", "ru-RU"),
        Language("sw", "Swahili", "Kiswahili", "sw-TZ"),
        Language("nl", "Dutch", "Nederlands", "nl-NL"),
        Language("id", "Indonesian", "Bahasa Indonesia", "id-ID"),
    )

    /** Targets shown first in the picker, in the order the product brief lists them. */
    val featuredTargets = listOf("en", "sw", "fr", "es", "ar", "de", "pt", "zh", "ja")

    private val byCode = all.associateBy { it.code }

    fun find(code: String?): Language? {
        if (code.isNullOrBlank()) return null
        val base = code.lowercase().substringBefore('-').substringBefore('_')
        return byCode[base]
    }

    fun nameOf(code: String?): String = find(code)?.name ?: code?.uppercase() ?: "Unknown"

    fun shortLabel(code: String?): String = find(code)?.code?.uppercase() ?: code?.uppercase() ?: "--"

    /** Targets ordered with the featured ones first. */
    fun targetsOrdered(): List<Language> {
        val featured = featuredTargets.mapNotNull { byCode[it] }
        return featured + all.filter { it.code !in featuredTargets }
    }

    fun isSupported(code: String?): Boolean = find(code) != null

    /** Pair key used for recents/favourites: "ar>en", or "auto>en". */
    fun pairKey(source: String?, target: String): String = "${source ?: "auto"}>$target"

    fun parsePair(key: String): Pair<String?, String>? {
        val parts = key.split('>')
        if (parts.size != 2) return null
        val src = parts[0].takeIf { it != "auto" }
        if (src != null && !isSupported(src)) return null
        if (!isSupported(parts[1])) return null
        return src to parts[1]
    }

    fun pairLabel(key: String): String {
        val (src, tgt) = parsePair(key) ?: return key
        return "${src?.let { nameOf(it) } ?: "Auto-detect"} → ${nameOf(tgt)}"
    }
}
