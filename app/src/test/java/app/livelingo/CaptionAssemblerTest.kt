package app.livelingo

import app.livelingo.pipeline.CaptionAssembler
import app.livelingo.speech.SpeechToken
import app.livelingo.speech.SpeechToken.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionAssemblerTest {

    private fun o(text: String, final: Boolean = true, lang: String = "ar") = SpeechToken(text, final, Kind.ORIGINAL, lang)
    private fun t(text: String, final: Boolean = true) = SpeechToken(text, final, Kind.TRANSLATION, "en")
    private val end = SpeechToken("", true, Kind.END_OF_SEGMENT)

    @Test
    fun provisionalTextIsReplacedFinalTextIsKept() {
        val a = CaptionAssembler()
        a.accept(listOf(o("ماذا")), listOf(o(" تف", false)), now = 0)
        a.accept(emptyList(), listOf(o(" تفعل", false)), now = 100)
        val s = a.snapshot()
        assertEquals("ماذا", s.originalFinal)
        assertEquals(" تفعل", s.originalProvisional)
        assertEquals("ar", s.originalLanguage)
    }

    @Test
    fun provisionalNeverOverwritesFinishedSentence() {
        val a = CaptionAssembler()
        a.accept(listOf(t("What are you doing tomorrow?")), emptyList(), now = 0)
        a.accept(emptyList(), listOf(t(" I th", false)), now = 50)
        a.accept(emptyList(), listOf(t(" I think", false)), now = 100)
        val s = a.snapshot()
        assertEquals("What are you doing tomorrow?", s.translationFinal)
        assertEquals(" I think", s.translationProvisional)
    }

    @Test
    fun utteranceAndTranslationAreCommittedTogether() {
        val a = CaptionAssembler()
        a.accept(listOf(o("ماذا"), o(" تفعل"), o(" غداً؟"), end), emptyList(), now = 1_000)
        a.accept(listOf(t("What are you doing tomorrow?")), emptyList(), now = 1_400)
        // Translation has settled and ends with punctuation.
        val commits = a.tick(now = 1_800, clearAfterMs = 6_000)
        assertEquals(1, commits.size)
        assertEquals("ماذا تفعل غداً؟", commits[0].original)
        assertEquals("What are you doing tomorrow?", commits[0].translation)
        assertEquals("ar", commits[0].sourceLanguage)
        assertEquals(400L, commits[0].translationLagMs)
        assertTrue(a.tick(now = 2_000, clearAfterMs = 6_000).isEmpty())
    }

    @Test
    fun commitWaitsForTranslationThenTimesOut() {
        val a = CaptionAssembler()
        a.accept(listOf(o("Hola"), end), emptyList(), now = 0)
        assertTrue("no translation yet", a.tick(now = 1_000, clearAfterMs = 60_000).isEmpty())
        val commits = a.tick(now = 6_500, clearAfterMs = 60_000)
        assertEquals(1, commits.size)
        assertEquals("", commits[0].translation)
    }

    @Test
    fun sameLanguageSpeechFillsBothStreams() {
        val a = CaptionAssembler()
        a.accept(listOf(SpeechToken("Hello there.", true, Kind.SAME_LANGUAGE, "en"), end), emptyList(), now = 0)
        val s = a.snapshot()
        assertEquals("Hello there.", s.originalFinal)
        assertEquals("Hello there.", s.translationFinal)
        val commits = a.tick(now = 700, clearAfterMs = 6_000)
        assertEquals("Hello there.", commits.single().translation)
    }

    @Test
    fun spaceIsInsertedBetweenUtterancesButNotForCjk() {
        val a = CaptionAssembler()
        a.accept(listOf(t("Hello."), end), emptyList(), now = 0)
        a.accept(listOf(t("Bye.")), emptyList(), now = 10)
        assertEquals("Hello. Bye.", a.snapshot().translationFinal)

        val b = CaptionAssembler()
        b.accept(listOf(SpeechToken("你好。", true, Kind.TRANSLATION, "zh"), end), emptyList(), now = 0)
        b.accept(listOf(SpeechToken("再见。", true, Kind.TRANSLATION, "zh")), emptyList(), now = 10)
        assertEquals("你好。再见。", b.snapshot().translationFinal)
    }

    @Test
    fun subtitlesClearAfterSilence() {
        val a = CaptionAssembler()
        a.accept(listOf(t("Hello.")), emptyList(), now = 0)
        a.tick(now = 1_000, clearAfterMs = 3_000)
        assertEquals("Hello.", a.snapshot().translationFinal)
        a.tick(now = 3_500, clearAfterMs = 3_000)
        assertTrue(a.snapshot().isEmpty)
    }

    @Test
    fun separateTranslatorDoesNotWipeOriginalProvisional() {
        val a = CaptionAssembler()
        a.accept(emptyList(), listOf(o("Hola qué", false)), now = 0, updatesOriginal = true, updatesTranslation = false)
        a.accept(emptyList(), listOf(t("Hello what", false)), now = 50, updatesOriginal = false, updatesTranslation = true)
        val s = a.snapshot()
        assertEquals("Hola qué", s.originalProvisional)
        assertEquals("Hello what", s.translationProvisional)
    }

    @Test
    fun displayBufferStaysBounded() {
        val a = CaptionAssembler(maxDisplayChars = 50)
        repeat(40) { a.accept(listOf(t("word$it ")), emptyList(), now = it.toLong()) }
        assertTrue(a.snapshot().translationFinal.length <= 50)
        assertTrue(a.snapshot().translationFinal.trim().endsWith("word39"))
    }
}
