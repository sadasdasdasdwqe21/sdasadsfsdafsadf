package app.livelingo.translation

import app.livelingo.speech.SpeechToken
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Contract for text translation engines used when the speech engine does not translate. */
interface TranslationProvider {
    val id: String
    val displayName: String
    fun supports(source: String, target: String): Boolean
    /** Downloads whatever the engine needs for this pair. Safe to call repeatedly. */
    suspend fun prepare(source: String, target: String)
    suspend fun translate(text: String, source: String, target: String): String
    fun close()
}

class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Google ML Kit on-device translation. Models (~30 MB each) download once, then work offline. */
class MlKitTranslationProvider : TranslationProvider {
    override val id = "mlkit"
    override val displayName = "ML Kit on-device"

    private val translators = HashMap<String, Translator>()
    private val ready = HashSet<String>()

    private fun mlCode(code: String): String? = TranslateLanguage.fromLanguageTag(code.substringBefore('-'))

    override fun supports(source: String, target: String) = mlCode(source) != null && mlCode(target) != null

    private fun translatorFor(source: String, target: String): Translator {
        val key = "$source>$target"
        return synchronized(translators) {
            translators.getOrPut(key) {
                val s = mlCode(source) ?: throw TranslationException("${source.uppercase()} is not available for on-device translation.")
                val t = mlCode(target) ?: throw TranslationException("${target.uppercase()} is not available for on-device translation.")
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(t).build())
            }
        }
    }

    override suspend fun prepare(source: String, target: String) {
        val key = "$source>$target"
        if (key in ready) return
        translatorFor(source, target).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        ready += key
    }

    override suspend fun translate(text: String, source: String, target: String): String {
        if (text.isBlank() || source == target) return text
        prepare(source, target)
        return translatorFor(source, target).translate(text).await()
    }

    override fun close() {
        synchronized(translators) {
            translators.values.forEach { runCatching { it.close() } }
            translators.clear()
        }
        ready.clear()
    }
}

/** Minimal Task -> coroutine bridge (avoids an extra dependency). */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(TranslationException(it.message ?: "Translation failed", it)) }
    addOnCanceledListener { cont.cancel() }
}

/**
 * Adds translation to engines that only transcribe. Finished utterances are translated in order
 * (a queue keeps sentences from overtaking each other). The live, unfinished sentence is
 * translated at most every [partialDebounceMs] and shown as provisional text.
 */
class TranslationManager(
    private val scope: CoroutineScope,
    private val provider: TranslationProvider,
    private val target: () -> String,
    private val emit: (finals: List<SpeechToken>, nonFinals: List<SpeechToken>) -> Unit,
    private val onError: (String) -> Unit,
    private val partialDebounceMs: Long = 350,
) {
    private val finals = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private var partialJob: Job? = null
    private var worker: Job? = null
    @Volatile private var latestPartial: Pair<String, String>? = null

    fun start() {
        worker = scope.launch {
            for ((text, lang) in finals) {
                partialJob?.cancel()
                val out = runCatching { provider.translate(text, lang, target()) }
                    .onFailure { onError(it.message ?: "Translation failed") }
                    .getOrNull() ?: continue
                emit(
                    listOf(
                        SpeechToken(out, true, SpeechToken.Kind.TRANSLATION, target()),
                        SpeechToken(" ", true, SpeechToken.Kind.TRANSLATION, target()),
                    ),
                    emptyList(),
                )
            }
        }
    }

    /** A finished utterance in [language]. */
    fun onFinal(text: String, language: String) {
        latestPartial = null
        if (text.isNotBlank()) finals.trySend(text to language)
    }

    /** The current unfinished utterance. */
    fun onPartial(text: String, language: String) {
        latestPartial = text to language
        if (partialJob?.isActive == true) return
        partialJob = scope.launch {
            delay(partialDebounceMs)
            val (t, l) = latestPartial ?: return@launch
            val out = runCatching { provider.translate(t, l, target()) }.getOrNull() ?: return@launch
            if (latestPartial != null) emit(emptyList(), listOf(SpeechToken(out, false, SpeechToken.Kind.TRANSLATION, target())))
        }
    }

    fun stop() {
        partialJob?.cancel()
        worker?.cancel()
        finals.close()
    }
}
