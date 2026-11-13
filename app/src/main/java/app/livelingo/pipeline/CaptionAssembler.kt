package app.livelingo.pipeline

import app.livelingo.core.CaptionDisplay
import app.livelingo.speech.SpeechToken

/** A finished utterance with its translation, ready for history. */
data class HistoryCommit(
    val original: String,
    val translation: String,
    val sourceLanguage: String?,
    val committedAt: Long,
    /** Time from the end of the utterance to its last translated word arriving. */
    val translationLagMs: Long?,
)

/**
 * Turns the token stream into subtitles.
 *
 * - Final text is appended once and never rewritten, so completed sentences stay stable.
 * - Provisional (non-final) text is replaced on every update and drawn dimmer by the overlay.
 * - Utterance boundaries pair each original sentence with its translation for history.
 *
 * Pure Kotlin with an injected clock value, so it is fully unit-testable.
 */
class CaptionAssembler(
    private val maxDisplayChars: Int = 600,
) {
    private val origFinal = StringBuilder()
    private val transFinal = StringBuilder()
    private var origProvisional = ""
    private var transProvisional = ""
    private var origNeedsSeparator = false
    private var transNeedsSeparator = false

    private val segmentOriginal = StringBuilder()
    private var segmentLanguage: String? = null
    private val pending = ArrayDeque<Pending>()
    private val uncommittedTranslation = StringBuilder()

    private var lastActivityAt = 0L
    private var lastTranslationFinalAt = 0L

    var detectedLanguage: String? = null
        private set

    private data class Pending(val text: String, val language: String?, val endedAt: Long)

    fun reset() {
        origFinal.clear(); transFinal.clear()
        origProvisional = ""; transProvisional = ""
        origNeedsSeparator = false; transNeedsSeparator = false
        segmentOriginal.clear(); segmentLanguage = null
        pending.clear(); uncommittedTranslation.clear()
        lastActivityAt = 0L; lastTranslationFinalAt = 0L
    }

    /**
     * Applies one engine update.
     *
     * [updatesOriginal] / [updatesTranslation] say which provisional streams this update owns.
     * A combined engine (Soniox with translation) owns both. When speech and translation come
     * from separate engines, each one replaces only its own provisional text.
     */
    fun accept(
        finals: List<SpeechToken>,
        nonFinals: List<SpeechToken>,
        now: Long,
        updatesOriginal: Boolean = true,
        updatesTranslation: Boolean = true,
    ) {
        for (t in finals) {
            when (t.kind) {
                SpeechToken.Kind.ORIGINAL -> {
                    appendOriginal(t.text, t.language)
                }
                SpeechToken.Kind.TRANSLATION -> {
                    appendTranslation(t.text, t.language)
                    lastTranslationFinalAt = now
                }
                SpeechToken.Kind.SAME_LANGUAGE -> {
                    appendOriginal(t.text, t.language)
                    appendTranslation(t.text, t.language)
                    lastTranslationFinalAt = now
                }
                SpeechToken.Kind.END_OF_SEGMENT -> endSegment(now)
            }
            if (t.text.isNotBlank()) lastActivityAt = now
        }

        val o = StringBuilder()
        val tr = StringBuilder()
        for (t in nonFinals) {
            when (t.kind) {
                SpeechToken.Kind.ORIGINAL -> o.append(t.text)
                SpeechToken.Kind.TRANSLATION -> tr.append(t.text)
                SpeechToken.Kind.SAME_LANGUAGE -> { o.append(t.text); tr.append(t.text) }
                SpeechToken.Kind.END_OF_SEGMENT -> Unit
            }
            if (t.kind != SpeechToken.Kind.TRANSLATION && t.language != null) detectedLanguage = t.language
            if (t.text.isNotBlank()) lastActivityAt = now
        }
        if (updatesOriginal) origProvisional = withSeparator(origFinal, o.toString(), origNeedsSeparator)
        if (updatesTranslation) transProvisional = withSeparator(transFinal, tr.toString(), transNeedsSeparator)
    }

    private fun appendOriginal(text: String, language: String?) {
        if (text.isEmpty()) return
        if (language != null) {
            detectedLanguage = language
            segmentLanguage = language
        }
        origFinal.append(withSeparator(origFinal, text, origNeedsSeparator))
        origNeedsSeparator = false
        segmentOriginal.append(text)
        trim(origFinal)
    }

    private fun appendTranslation(text: String, @Suppress("UNUSED_PARAMETER") language: String?) {
        if (text.isEmpty()) return
        transFinal.append(withSeparator(transFinal, text, transNeedsSeparator))
        transNeedsSeparator = false
        uncommittedTranslation.append(text)
        trim(transFinal)
    }

    private fun endSegment(now: Long) {
        val text = segmentOriginal.toString().trim()
        if (text.isNotEmpty()) pending.addLast(Pending(text, segmentLanguage, now))
        segmentOriginal.clear()
        origNeedsSeparator = true
        transNeedsSeparator = true
    }

    /**
     * Periodic housekeeping: commits finished utterances to history once their translation has
     * settled, and clears subtitles after a period of silence.
     */
    fun tick(now: Long, clearAfterMs: Long): List<HistoryCommit> {
        val commits = ArrayList<HistoryCommit>()
        if (pending.isNotEmpty()) {
            val oldest = pending.first()
            val newest = pending.last()
            val waited = now - oldest.endedAt
            val translationSettled = transProvisional.isBlank() &&
                (now - lastTranslationFinalAt >= 500 || endsSentence(uncommittedTranslation))
            val haveTranslation = uncommittedTranslation.isNotBlank()
            if ((haveTranslation && translationSettled && now - newest.endedAt >= 300) || waited >= 6_000) {
                val original = pending.joinToString(" ") { it.text }
                val translation = uncommittedTranslation.toString().trim()
                val lag = if (lastTranslationFinalAt >= newest.endedAt) lastTranslationFinalAt - newest.endedAt else null
                commits += HistoryCommit(original, translation, oldest.language, now, lag)
                pending.clear()
                uncommittedTranslation.clear()
            }
        }
        val idle = lastActivityAt > 0 && now - lastActivityAt >= clearAfterMs
        if (idle && origProvisional.isBlank() && transProvisional.isBlank() && (origFinal.isNotEmpty() || transFinal.isNotEmpty())) {
            origFinal.clear()
            transFinal.clear()
            origNeedsSeparator = false
            transNeedsSeparator = false
        }
        return commits
    }

    fun snapshot(): CaptionDisplay = CaptionDisplay(
        originalFinal = origFinal.toString(),
        originalProvisional = origProvisional,
        translationFinal = transFinal.toString(),
        translationProvisional = transProvisional,
        originalLanguage = detectedLanguage,
    )

    /** Inserts a space between utterances for space-separated scripts. */
    private fun withSeparator(existing: CharSequence, next: String, needed: Boolean): String {
        if (!needed || next.isEmpty() || existing.isEmpty()) return next
        val last = existing[existing.length - 1]
        val first = next[0]
        if (last.isWhitespace() || first.isWhitespace()) return next
        if (isCjk(last) || isCjk(first)) return next
        return " $next"
    }

    private fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    private fun endsSentence(sb: CharSequence): Boolean {
        val s = sb.trimEnd()
        if (s.isEmpty()) return false
        return s.last() in ".?!。？！؟…"
    }

    /** Keeps display buffers bounded, cutting at a word boundary. */
    private fun trim(sb: StringBuilder) {
        if (sb.length <= maxDisplayChars) return
        var cut = sb.length - maxDisplayChars
        while (cut < sb.length && !sb[cut].isWhitespace()) cut++
        if (cut >= sb.length) cut = sb.length - maxDisplayChars
        sb.delete(0, cut)
        while (sb.isNotEmpty() && sb[0].isWhitespace()) sb.deleteCharAt(0)
    }
}
