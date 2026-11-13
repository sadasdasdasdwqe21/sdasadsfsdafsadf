package app.livelingo

import app.livelingo.core.ErrorKind
import app.livelingo.speech.RecognitionConfig
import app.livelingo.speech.SonioxProtocol
import app.livelingo.speech.SpeechToken
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonioxProtocolTest {

    @Test
    fun configForAutoDetectWithInlineTranslation() {
        val json = JSONObject(
            SonioxProtocol.buildConfig(
                RecognitionConfig(sampleRate = 16000, sourceLanguage = null, targetLanguage = "en", translateInline = true, endpointDelayMs = 800, model = "stt-rt-v6")
            )
        )
        assertEquals("stt-rt-v6", json.getString("model"))
        assertEquals("pcm_s16le", json.getString("audio_format"))
        assertEquals(16000, json.getInt("sample_rate"))
        assertEquals(1, json.getInt("num_channels"))
        assertTrue(json.getBoolean("enable_language_identification"))
        assertTrue(json.getBoolean("enable_endpoint_detection"))
        assertFalse("auto-detect must not send hints", json.has("language_hints"))
        val t = json.getJSONObject("translation")
        assertEquals("one_way", t.getString("type"))
        assertEquals("en", t.getString("target_language"))
        assertFalse("key must never be inside the config", json.has("api_key"))
    }

    @Test
    fun configForManualSourceWithoutTranslation() {
        val json = JSONObject(
            SonioxProtocol.buildConfig(
                RecognitionConfig(sampleRate = 16000, sourceLanguage = "ar", targetLanguage = "sw", translateInline = false, endpointDelayMs = 100)
            )
        )
        assertEquals("ar", json.getJSONArray("language_hints").getString(0))
        assertFalse(json.has("translation"))
        assertEquals("endpoint delay is clamped to the API minimum", 500, json.getInt("max_endpoint_delay_ms"))
    }

    @Test
    fun parsesFinalAndNonFinalTokensWithTranslationStatus() {
        val msg = SonioxProtocol.parse(
            """
            {"tokens":[
              {"text":"ماذا","is_final":true,"language":"ar","translation_status":"original"},
              {"text":" تفعل","is_final":false,"language":"ar","translation_status":"original"},
              {"text":"What","is_final":true,"language":"en","source_language":"ar","translation_status":"translation"},
              {"text":"<end>","is_final":true}
            ],"final_audio_proc_ms":1200,"total_audio_proc_ms":1500}
            """.trimIndent()
        )
        assertNull(msg.error)
        assertEquals(3, msg.finalTokens.size)
        assertEquals(SpeechToken.Kind.ORIGINAL, msg.finalTokens[0].kind)
        assertEquals("ar", msg.finalTokens[0].language)
        assertEquals(SpeechToken.Kind.TRANSLATION, msg.finalTokens[1].kind)
        assertEquals(SpeechToken.Kind.END_OF_SEGMENT, msg.finalTokens[2].kind)
        assertEquals(1, msg.nonFinalTokens.size)
        assertEquals(1500L, msg.totalAudioMs)
        assertEquals(1200L, msg.finalAudioMs)
        assertFalse(msg.finished)
    }

    @Test
    fun sameLanguageTokensAreMarked() {
        val msg = SonioxProtocol.parse("""{"tokens":[{"text":"Hello","is_final":true,"language":"en","translation_status":"none"}]}""")
        assertEquals(SpeechToken.Kind.SAME_LANGUAGE, msg.finalTokens.single().kind)
    }

    @Test
    fun finishedMessage() {
        val msg = SonioxProtocol.parse("""{"tokens":[],"final_audio_proc_ms":5000,"total_audio_proc_ms":5000,"finished":true}""")
        assertTrue(msg.finished)
        assertTrue(msg.finalTokens.isEmpty())
    }

    @Test
    fun errorsMapToKindsAndRetryPolicy() {
        val auth = SonioxProtocol.parse("""{"tokens":[],"error_code":401,"error_type":"unauthenticated","error_message":"Invalid API key"}""").error
        assertNotNull(auth)
        assertEquals(ErrorKind.AUTH_FAILED, auth!!.kind)
        assertFalse(auth.retryable)

        val rate = SonioxProtocol.errorForCode(429, "Too many")
        assertEquals(ErrorKind.RATE_LIMITED, rate.kind)
        assertTrue(rate.retryable)
        assertTrue(rate.retryAfterMs >= 5_000)

        val credit = SonioxProtocol.errorForCode(402, "budget")
        assertEquals(ErrorKind.OUT_OF_CREDIT, credit.kind)
        assertFalse(credit.retryable)

        assertTrue(SonioxProtocol.errorForCode(503, "x").retryable)
        assertTrue(SonioxProtocol.errorForCode(413, "x").retryable)
    }
}
