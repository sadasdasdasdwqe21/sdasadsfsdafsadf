package app.livelingo

import app.livelingo.core.AudioSourceType
import app.livelingo.core.Languages
import app.livelingo.settings.AudioPreferences
import app.livelingo.settings.DisplayMode
import app.livelingo.settings.EnginePreferences
import app.livelingo.settings.LanguagePreferences
import app.livelingo.settings.SubtitlePreferences
import app.livelingo.settings.SubtitlePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAndLanguagesTest {

    @Test
    fun allRequiredSourceLanguagesAreRegistered() {
        val required = listOf("ar", "en", "es", "fr", "pt", "de", "it", "zh", "ja", "ko", "hi", "tr", "ru", "sw", "nl", "id")
        required.forEach { assertTrue("$it missing", Languages.isSupported(it)) }
        assertEquals(required.size, Languages.all.size)
        assertTrue(Languages.find("ar")!!.rtl)
        assertTrue(Languages.find("ar")!!.dialects.size >= 5)
    }

    @Test
    fun requiredTargetsComeFirst() {
        val firstNine = Languages.targetsOrdered().take(9).map { it.code }
        assertEquals(listOf("en", "sw", "fr", "es", "ar", "de", "pt", "zh", "ja"), firstNine)
    }

    @Test
    fun languageLookupIsTolerant() {
        assertEquals("Arabic", Languages.nameOf("ar-EG"))
        assertEquals("Chinese (Mandarin)", Languages.nameOf("zh_CN"))
        assertNull(Languages.find("xx"))
    }

    @Test
    fun pairKeysRoundTrip() {
        assertEquals("auto>en", Languages.pairKey(null, "en"))
        assertEquals(null to "en", Languages.parsePair("auto>en"))
        assertEquals("ar" to "sw", Languages.parsePair("ar>sw"))
        assertNull(Languages.parsePair("xx>en"))
        assertEquals("Auto-detect → English", Languages.pairLabel("auto>en"))
    }

    @Test
    fun languagePreferencesRoundTripAndRecents() {
        val p = LanguagePreferences(autoDetect = false, sourceLanguage = "es", targetLanguage = "sw", dialectLocale = "ar-EG")
            .withRecent("es>sw").withRecent("ar>en").withRecent("es>sw")
        val back = LanguagePreferences.fromJson(p.toJson())
        assertEquals(p, back)
        assertEquals(listOf("es>sw", "ar>en"), back.recentPairs)
        assertEquals("es", back.effectiveSource)
        assertEquals("es>sw", back.pairKey)
    }

    @Test
    fun subtitlePreferencesRoundTripAndClamp() {
        val p = SubtitlePreferences().applyPreset(SubtitlePreset.HIGH_CONTRAST).copy(displayMode = DisplayMode.ORIGINAL_AND_TRANSLATION, xFraction = 0.2f)
        assertEquals(p, SubtitlePreferences.fromJson(p.toJson()))
        val wild = SubtitlePreferences.fromJson("""{"fontSize":500,"maxLines":99,"bgOpacity":7}""")
        assertEquals(40f, wild.fontSizeSp, 0f)
        assertEquals(6, wild.maxLines)
        assertEquals(1f, wild.backgroundOpacity, 0f)
    }

    @Test
    fun defaultSubtitlesMatchTheBrief() {
        val d = SubtitlePreferences()
        assertEquals(0xFFFFFFFF.toInt(), d.textColor)
        assertTrue(d.backgroundOpacity in 0.3f..0.7f)
        assertTrue(d.cornerRadiusDp > 0)
        assertTrue(d.maxLines in 2..3)
        assertTrue("locked overlays pass taps through", d.locked && d.tapThrough)
    }

    @Test
    fun corruptJsonFallsBackToDefaults() {
        assertEquals(AudioPreferences(), AudioPreferences.fromJson("{not json"))
        assertEquals(EnginePreferences(), EnginePreferences.fromJson(null))
    }

    @Test
    fun audioAndEnginePreferencesRoundTrip() {
        val a = AudioPreferences(preferredSource = AudioSourceType.MICROPHONE, autoFallbackToMicrophone = false, silenceGating = false, vadSensitivity = 0.2f)
        assertEquals(a, AudioPreferences.fromJson(a.toJson()))
        val e = EnginePreferences(tokenServerUrl = "https://example.workers.dev/token", endpointDelayMs = 99999)
        val back = EnginePreferences.fromJson(e.toJson())
        assertEquals(3000, back.endpointDelayMs)
        assertFalse(back.tokenServerUrl.isBlank())
    }
}
