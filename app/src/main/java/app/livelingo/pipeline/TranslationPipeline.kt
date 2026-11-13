package app.livelingo.pipeline

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import app.livelingo.AppContainer
import app.livelingo.audio.AudioBufferManager
import app.livelingo.audio.AudioCaptureProvider
import app.livelingo.audio.AudioChunk
import app.livelingo.audio.CaptureEvent
import app.livelingo.audio.PreRollBuffer
import app.livelingo.audio.VoiceActivityDetector
import app.livelingo.core.AppError
import app.livelingo.core.AudioSourceType
import app.livelingo.core.CaptureStatus
import app.livelingo.core.ErrorKind
import app.livelingo.core.Languages
import app.livelingo.core.RecoveryAction
import app.livelingo.core.TranslationState
import app.livelingo.history.HistoryEntry
import app.livelingo.speech.RecognitionConfig
import app.livelingo.speech.SpeechEvent
import app.livelingo.speech.SpeechRecognitionProvider
import app.livelingo.speech.SpeechToken
import app.livelingo.translation.TranslationManager
import app.livelingo.translation.TranslationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Callbacks from the pipeline to its owner (the foreground service). */
interface PipelineListener {
    /** Internal capture is delivering silence while media plays. */
    fun onCaptureBlocked(reason: String)
    /** The audio stream died (device error, projection revoked). */
    fun onCaptureFailed(reason: String)
    /** A non-recoverable engine error. The pipeline has stopped sending audio. */
    fun onFatalError(error: AppError)
}

/**
 * capture thread -> bounded buffer -> VAD gate -> speech engine (WebSocket) -> caption assembler
 *                                                     \-> optional translator  -/
 *
 * Everything runs off the main thread. Reconnects use exponential backoff and skip stale audio,
 * so a network hiccup never leaves subtitles running seconds behind.
 */
class TranslationPipeline(
    private val context: Context,
    private val c: AppContainer,
    private val scope: CoroutineScope,
    private val listener: PipelineListener,
) {
    private sealed interface Outcome {
        data object Stopped : Outcome
        data object Paused : Outcome
        data object Restart : Outcome
        data class Failed(val failure: SpeechEvent.Failure) : Outcome
    }

    private val buffer = AudioBufferManager(capacity = 40)
    private val assembler = CaptionAssembler()
    private val paused = MutableStateFlow(false)
    private val stopped = AtomicBoolean(false)
    private val restartRequested = AtomicBoolean(false)

    private var capture: AudioCaptureProvider? = null
    @Volatile private var provider: SpeechRecognitionProvider? = null
    @Volatile private var translationManager: TranslationManager? = null
    private var loopJob: Job? = null
    private var tickJob: Job? = null

    @Volatile private var sessionId = 0
    @Volatile private var connected = false
    @Volatile private var sessionOutcome: Outcome? = null
    @Volatile private var lastTokenAt = 0L
    @Volatile private var speechSinceTokenMs = 0L
    @Volatile private var silentMs = 0L

    // Maps engine audio time to capture wall time, for real end-to-end latency.
    private val marks = ArrayDeque<LongArray>()
    private var audioSentMs = 0L
    private var bytesSent = 0L
    private var reconnects = 0
    private var startedAt = 0L

    private val tmSegment = StringBuilder()

    val sourceType: AudioSourceType? get() = capture?.type

    fun start(initialCapture: AudioCaptureProvider) {
        stopped.set(false)
        startedAt = SystemClock.elapsedRealtime()
        val battery = batteryLevel()
        c.session.updateDiagnostics { it.copy(sessionStartedAt = System.currentTimeMillis(), batteryAtStart = battery, batteryNow = battery, bufferCapacity = buffer.capacity) }
        startCapture(initialCapture)
        loopJob = scope.launch(Dispatchers.IO) { runLoop() }
        tickJob = scope.launch(Dispatchers.Default) {
            var n = 0
            while (isActive) {
                tick(++n)
                delay(200)
            }
        }
    }

    private fun startCapture(provider: AudioCaptureProvider) {
        capture = provider
        buffer.clear()
        provider.start(
            sink = { chunk -> buffer.offer(chunk) },
            events = { e -> scope.launch { onCaptureEvent(provider, e) } },
        )
        silentMs = 0
        val status = if (provider.type == AudioSourceType.INTERNAL) CaptureStatus.INTERNAL_ACTIVE else CaptureStatus.MICROPHONE_ACTIVE
        c.session.updateStatus { it.copy(capture = status, sourceType = provider.type) }
        c.session.updateDiagnostics { it.copy(audioInput = provider.description, sampleRate = provider.inputSampleRate) }
    }

    private fun onCaptureEvent(source: AudioCaptureProvider, e: CaptureEvent) {
        if (source !== capture) return
        when (e) {
            is CaptureEvent.Blocked -> {
                c.session.updateStatus { it.copy(capture = CaptureStatus.UNAVAILABLE) }
                listener.onCaptureBlocked(e.reason)
            }
            CaptureEvent.Unblocked -> c.session.updateStatus {
                it.copy(capture = CaptureStatus.INTERNAL_ACTIVE, error = it.error?.takeIf { err -> err.kind != ErrorKind.INTERNAL_CAPTURE_BLOCKED })
            }
            is CaptureEvent.Failed -> {
                c.session.updateStatus { it.copy(capture = CaptureStatus.UNAVAILABLE) }
                listener.onCaptureFailed(e.reason)
            }
        }
    }

    /** Swaps the audio source without tearing down the engine connection. */
    fun switchCapture(newCapture: AudioCaptureProvider) {
        val old = capture
        capture = null
        runCatching { old?.stop() }
        startCapture(newCapture)
        if (paused.value) newCapture.pause()
    }

    fun pause() {
        if (paused.value) return
        paused.value = true
        capture?.pause()
        c.session.updateStatus { it.copy(state = TranslationState.PAUSED, notice = null) }
    }

    fun resume() {
        if (!paused.value) return
        buffer.clear()
        capture?.resume()
        c.session.updateStatus { it.copy(state = TranslationState.STARTING) }
        paused.value = false
    }

    val isPaused: Boolean get() = paused.value

    /** Reconnects with fresh settings (for example a new target language). Capture keeps running. */
    fun restartEngine() {
        restartRequested.set(true)
        synchronized(assembler) { assembler.reset() }
        c.session.setCaptions(assembler.snapshot())
    }

    suspend fun stop() {
        stopped.set(true)
        paused.value = false
        withContext(NonCancellable) {
            runCatching { provider?.stop() }
            translationManager?.stop()
            loopJob?.cancel()
            tickJob?.cancel()
            runCatching { capture?.stop() }
            capture = null
        }
    }

    // ------------------------------------------------------------------------------------------

    private suspend fun runLoop() {
        var attempt = 0
        while (!stopped.get()) {
            if (paused.value) {
                c.session.updateDiagnostics { it.copy(connection = "Paused") }
                paused.first { !it }
                if (stopped.get()) break
            }
            val settings = c.settings.settings.value
            val probe = c.createSpeechProvider(settings.engine.speechEngine)
            if (probe.capabilities.needsNetwork && !c.network.online.value) {
                setError(AppError(ErrorKind.NO_INTERNET, "No internet connection. Subtitles resume automatically when you are back online.", RecoveryAction.WAIT, transient = true))
                c.session.updateDiagnostics { it.copy(networkOnline = false, connection = "Offline") }
                c.network.online.first { it }
                clearTransientError()
                c.session.updateDiagnostics { it.copy(networkOnline = true) }
            }
            when (val outcome = runSession(probe)) {
                Outcome.Stopped -> break
                Outcome.Paused -> continue
                Outcome.Restart -> attempt = 0
                is Outcome.Failed -> {
                    val f = outcome.failure
                    if (!f.retryable) {
                        val recovery = when (f.kind) {
                            ErrorKind.AUTH_FAILED, ErrorKind.NOT_CONFIGURED, ErrorKind.OUT_OF_CREDIT -> RecoveryAction.OPEN_ENGINE_SETTINGS
                            ErrorKind.MICROPHONE_PERMISSION_DENIED -> RecoveryAction.GRANT_MICROPHONE
                            else -> RecoveryAction.OPEN_ENGINE_SETTINGS
                        }
                        val err = AppError(f.kind, f.message, recovery)
                        setError(err)
                        listener.onFatalError(err)
                        break
                    }
                    attempt++
                    reconnects++
                    c.session.updateDiagnostics { it.copy(reconnects = reconnects, connection = "Reconnecting") }
                    setError(AppError(f.kind, "${f.message} Reconnecting…", RecoveryAction.WAIT, transient = true))
                    val backoff = min(15_000L, 500L * (1L shl min(attempt, 5)))
                    delay(maxOf(f.retryAfterMs, backoff))
                }
            }
        }
    }

    private suspend fun runSession(p: SpeechRecognitionProvider): Outcome {
        val settings = c.settings.settings.value
        val lang = settings.language
        val translator: TranslationProvider? = c.createTranslationProvider(settings.engine.translationEngine, p)
        val config = RecognitionConfig(
            sampleRate = app.livelingo.audio.PIPELINE_SAMPLE_RATE,
            sourceLanguage = lang.effectiveSource,
            targetLanguage = lang.targetLanguage,
            translateInline = translator == null,
            dialectLocale = lang.dialectLocale,
            endpointDelayMs = settings.engine.endpointDelayMs,
            model = settings.engine.sonioxModel,
        )
        val mySession = ++sessionId
        provider = p
        connected = false
        sessionOutcome = null
        audioSentMs = 0
        synchronized(marks) { marks.clear() }
        tmSegment.setLength(0)
        c.session.updateStatus {
            it.copy(
                state = if (paused.value) TranslationState.PAUSED else TranslationState.STARTING,
                sourceLanguage = lang.effectiveSource,
                targetLanguage = lang.targetLanguage,
                engineLabel = p.displayName + (translator?.let { t -> " + ${t.displayName}" } ?: ""),
            )
        }
        c.session.updateDiagnostics { it.copy(connection = "Connecting") }

        val tm = translator?.let { tr ->
            TranslationManager(
                scope = scope,
                provider = tr,
                target = { c.settings.settings.value.language.targetLanguage },
                emit = { finals, nonFinals ->
                    if (mySession == sessionId) {
                        synchronized(assembler) { assembler.accept(finals, nonFinals, SystemClock.elapsedRealtime(), updatesOriginal = false, updatesTranslation = true) }
                        publishCaptions()
                    }
                },
                onError = { msg -> c.session.updateStatus { s -> s.copy(notice = "Translation: $msg") } },
            ).also { it.start() }
        }
        translationManager = tm

        val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        val consumer = scope.launch(Dispatchers.Default) {
            for (ev in events) handleEvent(ev, mySession, translator != null)
        }
        p.start(config) { ev -> events.trySend(ev) }

        val gating = settings.audio.silenceGating && p.capabilities.needsNetwork
        val vad = VoiceActivityDetector(settings.audio.vadSensitivity)
        val preRoll = PreRollBuffer(400)
        var gateOpen = !gating
        var lastSendAt = SystemClock.elapsedRealtime()
        val connectDeadline = SystemClock.elapsedRealtime() + 12_000

        val outcome: Outcome = run {
            while (true) {
                sessionOutcome?.let { return@run it }
                if (stopped.get()) return@run Outcome.Stopped
                if (paused.value) {
                    p.finalizeNow()
                    return@run Outcome.Paused
                }
                if (restartRequested.getAndSet(false)) return@run Outcome.Restart

                if (!connected) {
                    if (SystemClock.elapsedRealtime() > connectDeadline) {
                        return@run Outcome.Failed(SpeechEvent.Failure(ErrorKind.TRANSLATION_UNAVAILABLE, "The speech engine did not answer.", retryable = true, retryAfterMs = 1_000))
                    }
                    delay(20)
                    continue
                }

                val chunk = buffer.poll(100)
                val now = SystemClock.elapsedRealtime()
                // Silence is not streamed when gating is on, so keep the idle socket alive
                // (Soniox closes connections that are quiet for 40 s).
                if (now - lastSendAt > 10_000) {
                    p.keepAlive()
                    lastSendAt = now
                }
                if (chunk == null) continue

                val v = vad.process(chunk.samples, chunk.durationMs)
                silentMs = if (v.levelDb < -85f) silentMs + chunk.durationMs else 0
                if (v.speech) speechSinceTokenMs += chunk.durationMs
                c.session.updateDiagnostics { it.copy(levelDb = v.levelDb, speechDetected = v.speech, bufferQueued = buffer.size, droppedChunks = buffer.droppedCount) }

                if (p.pendingBytes() > 256_000) {
                    return@run Outcome.Failed(SpeechEvent.Failure(ErrorKind.NO_INTERNET, "The network is too slow to keep up.", retryable = true, retryAfterMs = 500))
                }

                if (!gating) {
                    if (send(p, chunk)) lastSendAt = now
                } else if (v.speech) {
                    if (!gateOpen) {
                        gateOpen = true
                        for (pre in preRoll.drain()) send(p, pre)
                    }
                    if (send(p, chunk)) lastSendAt = now
                } else if (gateOpen) {
                    gateOpen = false
                    send(p, chunk)
                    p.finalizeNow()
                    lastSendAt = now
                } else {
                    preRoll.add(chunk)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Outcome.Stopped
        }

        sessionId++ // ignore late events from this session
        withContext(NonCancellable) {
            runCatching { p.stop() }
            tm?.stop()
            translator?.close()
        }
        translationManager = null
        events.close()
        consumer.cancel()
        connected = false
        c.session.updateDiagnostics { it.copy(connection = "Disconnected") }
        return outcome
    }

    private fun send(p: SpeechRecognitionProvider, chunk: AudioChunk): Boolean {
        if (!p.sendAudio(chunk)) return false
        audioSentMs += chunk.durationMs
        bytesSent += chunk.samples.size * 2L
        synchronized(marks) {
            marks.addLast(longArrayOf(audioSentMs, chunk.captureTimeMs))
            while (marks.size > 600) marks.removeFirst()
        }
        return true
    }

    private fun captureTimeFor(audioMs: Long): Long? = synchronized(marks) {
        while (marks.size > 1 && marks.first()[0] < audioMs - 100) marks.removeFirst()
        marks.firstOrNull { it[0] >= audioMs }?.get(1)
    }

    private fun handleEvent(ev: SpeechEvent, mySession: Int, separateTranslator: Boolean) {
        if (mySession != sessionId) return
        when (ev) {
            SpeechEvent.Connected -> {
                connected = true
                buffer.dropOlderThan(SystemClock.elapsedRealtime(), 1_500)
                clearTransientError()
                c.session.updateStatus { it.copy(state = if (paused.value) TranslationState.PAUSED else TranslationState.TRANSLATING) }
                c.session.updateDiagnostics { it.copy(connection = "Connected", networkOnline = true) }
            }
            is SpeechEvent.Tokens -> {
                val now = SystemClock.elapsedRealtime()
                synchronized(assembler) {
                    assembler.accept(ev.finalTokens, ev.nonFinalTokens, now, updatesOriginal = true, updatesTranslation = !separateTranslator)
                }
                if (ev.finalTokens.any { it.text.isNotBlank() } || ev.nonFinalTokens.any { it.text.isNotBlank() }) {
                    lastTokenAt = now
                    speechSinceTokenMs = 0
                }
                translationManager?.let { feedTranslator(it, ev) }
                ev.processedAudioMs?.let { ms ->
                    captureTimeFor(ms)?.let { t ->
                        val latency = (now - t).coerceAtLeast(0)
                        c.session.updateDiagnostics { d ->
                            d.copy(recognitionLatencyMs = latency, totalLatencyMs = latency + (d.translationLatencyMs ?: 0))
                        }
                    }
                }
                val detected = assembler.detectedLanguage
                if (detected != null && detected != c.session.status.value.detectedLanguage) {
                    c.session.updateStatus { it.copy(detectedLanguage = detected) }
                    c.session.updateDiagnostics { it.copy(detectedLanguage = detected) }
                }
                publishCaptions()
            }
            is SpeechEvent.Failure -> if (sessionOutcome == null) sessionOutcome = Outcome.Failed(ev)
            SpeechEvent.Finished -> if (sessionOutcome == null) sessionOutcome = Outcome.Restart
        }
    }

    /** Builds utterances for the separate translator from the original-language tokens. */
    private fun feedTranslator(tm: TranslationManager, ev: SpeechEvent.Tokens) {
        val fallbackLang = c.settings.settings.value.language.effectiveSource ?: assembler.detectedLanguage
        for (t in ev.finalTokens) {
            when (t.kind) {
                SpeechToken.Kind.ORIGINAL, SpeechToken.Kind.SAME_LANGUAGE -> tmSegment.append(t.text)
                SpeechToken.Kind.END_OF_SEGMENT -> {
                    val lang = t.language ?: fallbackLang
                    val text = tmSegment.toString().trim()
                    tmSegment.setLength(0)
                    if (lang != null && text.isNotEmpty()) tm.onFinal(text, lang)
                }
                SpeechToken.Kind.TRANSLATION -> Unit
            }
        }
        val partial = ev.nonFinalTokens.filter { it.kind == SpeechToken.Kind.ORIGINAL }.joinToString("") { it.text }
        val lang = ev.nonFinalTokens.firstOrNull { it.language != null }?.language ?: fallbackLang
        if (lang != null && (partial.isNotBlank() || tmSegment.isNotBlank())) tm.onPartial((tmSegment.toString() + partial).trim(), lang)
    }

    private fun publishCaptions() {
        val snap = synchronized(assembler) { assembler.snapshot() }
        c.session.setCaptions(snap)
    }

    private fun tick(n: Int) {
        val now = SystemClock.elapsedRealtime()
        val s = c.settings.settings.value
        val commits = synchronized(assembler) { assembler.tick(now, s.subtitle.clearAfterSeconds * 1000L) }
        publishCaptions()
        for (commit in commits) {
            commit.translationLagMs?.let { lag ->
                c.session.updateDiagnostics { d -> d.copy(translationLatencyMs = lag, totalLatencyMs = (d.recognitionLatencyMs ?: 0) + lag) }
            }
            if (s.general.historyEnabled) {
                val entry = HistoryEntry(
                    createdAt = System.currentTimeMillis(),
                    sourceLanguage = commit.sourceLanguage,
                    targetLanguage = s.language.targetLanguage,
                    original = commit.original,
                    translation = commit.translation.ifBlank { commit.original },
                )
                scope.launch(Dispatchers.IO) { runCatching { c.history.add(entry) } }
            }
        }

        // Honest capture status: active, or waiting because nothing is playing.
        val cap = capture
        if (cap != null && !paused.value) {
            val current = c.session.status.value.capture
            if (current != CaptureStatus.UNAVAILABLE) {
                val next = when {
                    silentMs >= 2_000 -> CaptureStatus.WAITING_FOR_AUDIO
                    cap.type == AudioSourceType.INTERNAL -> CaptureStatus.INTERNAL_ACTIVE
                    else -> CaptureStatus.MICROPHONE_ACTIVE
                }
                if (next != current) c.session.updateStatus { it.copy(capture = next) }
            }
        }

        // Speech is clearly present but nothing comes back: say so instead of failing silently.
        if (connected && speechSinceTokenMs > 15_000 && now - lastTokenAt > 15_000) {
            speechSinceTokenMs = 0
            val src = s.language.effectiveSource
            val hint = if (src != null) "Check that the speaker is using ${Languages.nameOf(src)}, or switch on auto-detect." else "The audio may be music or too noisy to recognise."
            c.session.updateStatus { it.copy(notice = "Sound detected but no speech recognised. $hint") }
        }

        if (n % 5 == 0) {
            c.session.updateDiagnostics {
                it.copy(audioSentSeconds = audioSentTotalSeconds(), bytesSent = bytesSent, bufferQueued = buffer.size, droppedChunks = buffer.droppedCount, networkOnline = c.network.online.value)
            }
            if (n % 150 == 0) c.session.updateDiagnostics { it.copy(batteryNow = batteryLevel()) }
        }
    }

    private var audioSentAccumulator = 0.0
    private var lastAudioSentSeen = 0L
    private fun audioSentTotalSeconds(): Double {
        val delta = audioSentMs - lastAudioSentSeen
        if (delta > 0) audioSentAccumulator += delta / 1000.0
        lastAudioSentSeen = audioSentMs
        return audioSentAccumulator
    }

    private fun setError(e: AppError) = c.session.updateStatus { it.copy(error = e) }

    private fun clearTransientError() = c.session.updateStatus {
        if (it.error?.transient == true) it.copy(error = null) else it
    }

    private fun batteryLevel(): Int = runCatching {
        context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }.getOrDefault(-1)
}
