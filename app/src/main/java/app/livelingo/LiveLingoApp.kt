package app.livelingo

import android.app.Application
import android.content.Context
import android.os.Build
import app.livelingo.core.SessionStore
import app.livelingo.history.HistoryDatabase
import app.livelingo.history.TranslationHistoryRepository
import app.livelingo.network.ApiClient
import app.livelingo.network.CredentialProvider
import app.livelingo.network.NetworkMonitor
import app.livelingo.settings.CredentialMode
import app.livelingo.settings.EnginePreferences
import app.livelingo.settings.SecureStore
import app.livelingo.settings.SettingsRepository
import app.livelingo.settings.SpeechEngine
import app.livelingo.settings.TranslationEngine
import app.livelingo.speech.OnDeviceSpeechProvider
import app.livelingo.speech.SonioxSpeechProvider
import app.livelingo.speech.SpeechRecognitionProvider
import app.livelingo.translation.MlKitTranslationProvider
import app.livelingo.translation.TranslationProvider
import app.livelingo.core.AppError
import app.livelingo.core.ErrorKind
import app.livelingo.core.RecoveryAction
import app.livelingo.core.TranslationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LiveLingoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as LiveLingoApp).container

/**
 * Dependency container (manual constructor injection). Every component receives its
 * collaborators through its constructor, which keeps engines swappable and classes testable
 * without a code-generating DI framework.
 */
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session = SessionStore()
    val settings = SettingsRepository(app, appScope)
    val secureStore = SecureStore(app)
    val http = ApiClient.create()
    val network = NetworkMonitor(app)
    val credentials = CredentialProvider(settings, secureStore, http)

    private val database by lazy { HistoryDatabase.create(app) }
    val history by lazy { TranslationHistoryRepository(database.history()) }

    init {
        // A session flag still set at process start means Android killed us mid-session
        // (typically Samsung battery optimisation). Tell the user instead of failing silently.
        appScope.launch {
            if (settings.wasSessionActive() && !session.status.value.isActive) {
                settings.setSessionActive(false)
                session.updateStatus {
                    it.copy(
                        state = TranslationState.ERROR,
                        error = AppError(
                            ErrorKind.BATTERY_OPTIMISATION_KILL,
                            "LiveLingo was closed by the system while translating. Setting its battery use to Unrestricted prevents this.",
                            RecoveryAction.OPEN_BATTERY_SETTINGS,
                        ),
                    )
                }
            }
        }
    }

    /** True when the selected speech engine has what it needs to start (key, server or offline support). */
    fun engineReady(e: EnginePreferences = settings.settings.value.engine): Boolean {
        return when (e.speechEngine) {
            SpeechEngine.ON_DEVICE -> OnDeviceSpeechProvider.isSupportedOnThisPhone(app)
            SpeechEngine.SONIOX -> when (e.credentialMode) {
                CredentialMode.DEVICE_KEY -> secureStore.has(SecureStore.SONIOX_API_KEY)
                CredentialMode.TOKEN_SERVER -> e.tokenServerUrl.startsWith("https://")
            }
        }
    }

    /** Engine registry: one place to add new speech engines. */
    fun createSpeechProvider(engine: SpeechEngine): SpeechRecognitionProvider = when (engine) {
        SpeechEngine.SONIOX -> SonioxSpeechProvider(http, credentials)
        SpeechEngine.ON_DEVICE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) OnDeviceSpeechProvider(app)
            else SonioxSpeechProvider(http, credentials)
    }

    /** Separate translator, only when the speech engine is not translating inline. */
    fun createTranslationProvider(engine: TranslationEngine, speech: SpeechRecognitionProvider): TranslationProvider? =
        if (engine == TranslationEngine.INLINE && speech.capabilities.inlineTranslation) null
        else MlKitTranslationProvider()
}
