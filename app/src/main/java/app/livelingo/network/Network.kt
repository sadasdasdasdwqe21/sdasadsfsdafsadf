package app.livelingo.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.livelingo.core.ErrorKind
import app.livelingo.settings.CredentialMode
import app.livelingo.settings.SecureStore
import app.livelingo.settings.SettingsRepository
import app.livelingo.speech.SpeechEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One shared HTTP/WebSocket client: connection reuse, TLS only, WebSocket pings. */
object ApiClient {
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)       // streaming socket: no read timeout
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)          // detects dead connections quickly
        .retryOnConnectionFailure(true)
        .build()
}

/** Live online/offline state from the system's default network. */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _online.value = true }
                override fun onLost(network: Network) { _online.value = currentlyOnline() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        }
    }

    private fun currentlyOnline(): Boolean {
        val c = cm ?: return false
        val caps = c.getNetworkCapabilities(c.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

class CredentialException(val kind: ErrorKind, message: String, val retryable: Boolean) : Exception(message)

/**
 * Supplies the key for the Soniox connection.
 *
 * TOKEN_SERVER: the phone asks your own small server (see backend/) for a short-lived temporary
 * key. The real Soniox key stays on the server. Recommended if the app is shared with anyone.
 *
 * DEVICE_KEY: the key you paste in Engine settings, encrypted with the Android Keystore. Simple
 * for personal use; never placed in the source code or the APK.
 */
class CredentialProvider(
    private val settings: SettingsRepository,
    private val secure: SecureStore,
    private val http: OkHttpClient,
) {
    suspend fun sonioxKey(): Result<String> = withContext(Dispatchers.IO) {
        val engine = settings.settings.value.engine
        when (engine.credentialMode) {
            CredentialMode.DEVICE_KEY -> {
                val key = secure.get(SecureStore.SONIOX_API_KEY)
                if (key.isNullOrBlank()) {
                    Result.failure(CredentialException(ErrorKind.NOT_CONFIGURED, "Add your Soniox API key in Engine settings to start translating.", false))
                } else {
                    Result.success(key)
                }
            }
            CredentialMode.TOKEN_SERVER -> fetchTemporaryKey(engine.tokenServerUrl)
        }
    }

    private fun fetchTemporaryKey(url: String): Result<String> {
        if (url.isBlank() || !url.startsWith("https://")) {
            return Result.failure(CredentialException(ErrorKind.NOT_CONFIGURED, "Set an https:// token server address in Engine settings.", false))
        }
        val secret = secure.get(SecureStore.TOKEN_SERVER_SECRET).orEmpty()
        val req = Request.Builder()
            .url(url)
            .header("X-LiveLingo-Secret", secret)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 || resp.code == 403 ->
                        Result.failure(CredentialException(ErrorKind.AUTH_FAILED, "The token server refused this phone. Check the client secret.", false))
                    resp.code == 429 ->
                        Result.failure(CredentialException(ErrorKind.RATE_LIMITED, "The token server is rate limiting requests.", true))
                    !resp.isSuccessful ->
                        Result.failure(CredentialException(ErrorKind.TRANSLATION_UNAVAILABLE, "Token server error ${resp.code}.", true))
                    else -> {
                        val key = runCatching { JSONObject(body).optString("api_key") }.getOrNull()
                        if (key.isNullOrBlank()) {
                            Result.failure(CredentialException(ErrorKind.TRANSLATION_UNAVAILABLE, "Token server reply had no api_key.", false))
                        } else {
                            Result.success(key)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Result.failure(CredentialException(ErrorKind.NO_INTERNET, "Could not reach the token server (${e.message ?: "network error"}).", true))
        }
    }

    fun toFailure(e: Throwable): SpeechEvent.Failure = when (e) {
        is CredentialException -> SpeechEvent.Failure(e.kind, e.message ?: "Credential error", e.retryable, if (e.retryable) 3_000 else 0)
        else -> SpeechEvent.Failure(ErrorKind.TRANSLATION_UNAVAILABLE, e.message ?: "Credential error", true, 3_000)
    }
}
