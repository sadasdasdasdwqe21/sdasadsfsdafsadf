package app.livelingo.ui

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.livelingo.audio.AudioEnvironment
import app.livelingo.audio.PIPELINE_SAMPLE_RATE
import app.livelingo.container
import app.livelingo.core.Languages
import app.livelingo.history.HistoryEntry
import app.livelingo.service.ServiceController
import app.livelingo.settings.AudioPreferences
import app.livelingo.settings.CredentialMode
import app.livelingo.settings.EnginePreferences
import app.livelingo.settings.LanguagePreferences
import app.livelingo.settings.SecureStore
import app.livelingo.settings.SpeechEngine
import app.livelingo.settings.SubtitlePreferences
import app.livelingo.speech.OnDeviceSpeechProvider
import app.livelingo.speech.RecognitionConfig
import app.livelingo.speech.SonioxSpeechProvider
import app.livelingo.speech.SpeechEvent
import app.livelingo.translation.MlKitTranslationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SetupState(
    val overlay: Boolean = false,
    val audio: Boolean = false,
    val notifications: Boolean = true,
    val batteryUnrestricted: Boolean = false,
    val engineConfigured: Boolean = false,
    val headphones: Boolean = false,
    val onDeviceAvailable: Boolean = false,
) {
    val ready get() = overlay && audio && engineConfigured
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container

    val settings = c.settings.settings
    val status = c.session.status
    val captions = c.session.captions
    val diagnostics = c.session.diagnostics
    val history: StateFlow<List<HistoryEntry>> = c.history.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _setup = MutableStateFlow(SetupState())
    val setup: StateFlow<SetupState> = _setup.asStateFlow()

    private val _engineMessage = MutableStateFlow<String?>(null)
    val engineMessage: StateFlow<String?> = _engineMessage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val ctx: Context get() = getApplication()

    fun refreshSetup() {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val engine = settings.value.engine
        val configured = when (engine.speechEngine) {
            SpeechEngine.ON_DEVICE -> OnDeviceSpeechProvider.isSupportedOnThisPhone(ctx)
            SpeechEngine.SONIOX -> when (engine.credentialMode) {
                CredentialMode.DEVICE_KEY -> c.secureStore.has(SecureStore.SONIOX_API_KEY)
                CredentialMode.TOKEN_SERVER -> engine.tokenServerUrl.startsWith("https://")
            }
        }
        _setup.value = SetupState(
            overlay = Settings.canDrawOverlays(ctx),
            audio = granted(Manifest.permission.RECORD_AUDIO),
            notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS),
            batteryUnrestricted = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true,
            engineConfigured = configured,
            headphones = AudioEnvironment.headphonesConnected(ctx),
            onDeviceAvailable = OnDeviceSpeechProvider.isSupportedOnThisPhone(ctx),
        )
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    // --- Session --------------------------------------------------------------------------------

    fun start() = ServiceController.launchStartFlow(ctx)
    fun stop() = ServiceController.stop(ctx)
    fun pause() = ServiceController.pause(ctx)
    fun resume() = ServiceController.resume(ctx)
    fun dismissError() = c.session.updateStatus { it.copy(error = null, notice = null, state = if (it.isActive) it.state else app.livelingo.core.TranslationState.OFF) }

    private fun notifyEngineChanged() {
        if (status.value.isActive) ServiceController.send(ctx, ServiceController.ACTION_ENGINE_CHANGED)
    }

    // --- Preferences ----------------------------------------------------------------------------

    fun updateLanguage(block: (LanguagePreferences) -> LanguagePreferences) = viewModelScope.launch {
        c.settings.updateLanguage(block)
        c.session.updateStatus { it.copy(targetLanguage = c.settings.current().language.targetLanguage) }
        notifyEngineChanged()
    }

    fun applyPair(key: String) {
        val (src, tgt) = Languages.parsePair(key) ?: return
        updateLanguage { it.copy(autoDetect = src == null, sourceLanguage = src ?: it.sourceLanguage, targetLanguage = tgt) }
    }

    fun updateSubtitle(block: (SubtitlePreferences) -> SubtitlePreferences) = viewModelScope.launch { c.settings.updateSubtitle(block) }

    fun updateAudio(block: (AudioPreferences) -> AudioPreferences) = viewModelScope.launch { c.settings.updateAudio(block) }

    fun updateEngine(block: (EnginePreferences) -> EnginePreferences) = viewModelScope.launch {
        c.settings.updateEngine(block)
        refreshSetup()
        notifyEngineChanged()
    }

    fun setHistoryEnabled(enabled: Boolean) = viewModelScope.launch { c.settings.updateGeneral { it.copy(historyEnabled = enabled) } }

    // --- Credentials ----------------------------------------------------------------------------

    fun hasSonioxKey() = c.secureStore.has(SecureStore.SONIOX_API_KEY)
    fun hasTokenSecret() = c.secureStore.has(SecureStore.TOKEN_SERVER_SECRET)

    fun saveSonioxKey(key: String) {
        c.secureStore.put(SecureStore.SONIOX_API_KEY, key)
        _engineMessage.value = if (key.isBlank()) "Key removed." else "Key saved and encrypted on this phone."
        refreshSetup()
    }

    fun saveTokenSecret(secret: String) {
        c.secureStore.put(SecureStore.TOKEN_SERVER_SECRET, secret)
        _engineMessage.value = if (secret.isBlank()) "Client secret removed." else "Client secret saved."
    }

    /** Opens a real session to the speech engine with the current settings, then closes it. */
    fun testConnection() = viewModelScope.launch {
        _busy.value = true
        _engineMessage.value = "Connecting…"
        val s = c.settings.current()
        val provider = SonioxSpeechProvider(c.http, c.credentials)
        val result = CompletableDeferred<String>()
        val lateFailure = CompletableDeferred<String>()
        provider.start(
            RecognitionConfig(
                sampleRate = PIPELINE_SAMPLE_RATE,
                sourceLanguage = s.language.effectiveSource,
                targetLanguage = s.language.targetLanguage,
                translateInline = true,
                endpointDelayMs = s.engine.endpointDelayMs,
                model = s.engine.sonioxModel,
            )
        ) { ev ->
            when (ev) {
                SpeechEvent.Connected -> result.complete("connected")
                is SpeechEvent.Failure -> if (!result.complete("error:" + ev.message)) lateFailure.complete(ev.message)
                else -> Unit
            }
        }
        val r = withTimeoutOrNull(10_000) { result.await() } ?: "error:No answer within 10 seconds."
        if (r == "connected") {
            // Give the server a moment to reject the configuration if anything is wrong with it.
            val late = withTimeoutOrNull(1_500) { lateFailure.await() }
            _engineMessage.value = late ?: "Connected. Soniox accepted the key and the ${s.engine.sonioxModel} settings."
        } else {
            _engineMessage.value = r.removePrefix("error:")
        }
        provider.stop()
        _busy.value = false
    }

    fun checkOnDeviceModel() = viewModelScope.launch {
        val lang = settings.value.language
        val locale = lang.dialectLocale?.takeIf { it.startsWith(lang.sourceLanguage) } ?: Languages.find(lang.sourceLanguage)?.onDeviceLocale ?: "en-US"
        _engineMessage.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OnDeviceSpeechProvider.checkModel(ctx, locale)
        } else "On-device recognition with captured audio needs Android 13 or newer."
    }

    fun downloadOnDeviceModel() {
        val lang = settings.value.language
        val locale = lang.dialectLocale?.takeIf { it.startsWith(lang.sourceLanguage) } ?: Languages.find(lang.sourceLanguage)?.onDeviceLocale ?: "en-US"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OnDeviceSpeechProvider.downloadModel(ctx, locale)
            _engineMessage.value = "Download requested for $locale. Android shows its progress in the notification shade."
        }
    }

    fun downloadTranslationModel() = viewModelScope.launch {
        val lang = settings.value.language
        val src = lang.effectiveSource
        if (src == null) {
            _engineMessage.value = "Choose a source language first (auto-detect downloads models as languages appear)."
            return@launch
        }
        _busy.value = true
        _engineMessage.value = "Downloading ${Languages.nameOf(src)} → ${Languages.nameOf(lang.targetLanguage)} translation model…"
        val provider = MlKitTranslationProvider()
        _engineMessage.value = runCatching { provider.prepare(src, lang.targetLanguage) }
            .fold({ "Translation model ready for offline use." }, { "Model download failed: ${it.message}" })
        provider.close()
        _busy.value = false
    }

    fun clearEngineMessage() { _engineMessage.value = null }

    // --- History --------------------------------------------------------------------------------

    fun clearHistory() = viewModelScope.launch(Dispatchers.IO) { c.history.clear() }
    fun deleteHistory(id: Long) = viewModelScope.launch(Dispatchers.IO) { c.history.delete(id) }

    fun copy(text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("LiveLingo", text))
    }

    /** Writes the transcript to a private cache file and returns a share intent for it. */
    suspend fun exportIntent(): Intent? = withContext(Dispatchers.IO) {
        val text = c.history.exportText()
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val file = File(dir, "livelingo-transcript-$stamp.txt")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "LiveLingo transcript")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
