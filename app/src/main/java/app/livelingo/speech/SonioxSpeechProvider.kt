package app.livelingo.speech

import app.livelingo.audio.AudioChunk
import app.livelingo.core.ErrorKind
import app.livelingo.network.CredentialProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Soniox real-time engine: one WebSocket carries streaming recognition, automatic language
 * identification and translation. Audio is sent as 100 ms binary frames on the same connection,
 * never one HTTP request per fragment.
 */
class SonioxSpeechProvider(
    private val http: OkHttpClient,
    private val credentials: CredentialProvider,
) : SpeechRecognitionProvider {

    override val id = "soniox"
    override val displayName = "Soniox real-time"
    override val capabilities = EngineCapabilities(
        streaming = true, automaticLanguageId = true, inlineTranslation = true, needsNetwork = true,
    )

    @Volatile private var socket: WebSocket? = null
    @Volatile private var open = false
    private var finished: CompletableDeferred<Unit>? = null
    private val closedByUs = AtomicBoolean(false)

    override suspend fun start(config: RecognitionConfig, listener: (SpeechEvent) -> Unit) {
        closedByUs.set(false)
        val key = credentials.sonioxKey().getOrElse { e ->
            listener(credentials.toFailure(e))
            return
        }
        val done = CompletableDeferred<Unit>()
        finished = done
        val request = Request.Builder()
            .url(SonioxProtocol.ENDPOINT)
            .header("Authorization", "Bearer $key")
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(SonioxProtocol.buildConfig(config))
                open = true
                listener(SpeechEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { SonioxProtocol.parse(text) }.getOrElse {
                    listener(SpeechEvent.Failure(ErrorKind.SPEECH_RECOGNITION_FAILED, "Unreadable reply from the speech engine.", retryable = true, retryAfterMs = 1_000))
                    return
                }
                if (msg.error != null) {
                    listener(msg.error)
                    return
                }
                if (msg.finalTokens.isNotEmpty() || msg.nonFinalTokens.isNotEmpty() || msg.totalAudioMs != null) {
                    listener(SpeechEvent.Tokens(msg.finalTokens, msg.nonFinalTokens, msg.totalAudioMs))
                }
                if (msg.finished) {
                    done.complete(Unit)
                    if (!closedByUs.get()) listener(SpeechEvent.Finished)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open = false
                done.complete(Unit)
                if (!closedByUs.get()) {
                    listener(SpeechEvent.Failure(ErrorKind.TRANSLATION_UNAVAILABLE, "Connection closed by the server ($code).", retryable = true, retryAfterMs = 500))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open = false
                done.complete(Unit)
                if (closedByUs.get()) return
                val failure = when {
                    response != null -> SonioxProtocol.errorForCode(response.code, response.message)
                    t is UnknownHostException -> SpeechEvent.Failure(ErrorKind.NO_INTERNET, "No internet connection.", retryable = true, retryAfterMs = 2_000)
                    t is IOException -> SpeechEvent.Failure(ErrorKind.NO_INTERNET, "Network connection lost (${t.message ?: t.javaClass.simpleName}).", retryable = true, retryAfterMs = 1_500)
                    else -> SpeechEvent.Failure(ErrorKind.SPEECH_RECOGNITION_FAILED, t.message ?: "Connection failed.", retryable = true, retryAfterMs = 2_000)
                }
                listener(failure)
            }
        })
    }

    override fun sendAudio(chunk: AudioChunk): Boolean {
        val ws = socket ?: return false
        if (!open) return false
        return ws.send(chunk.toLittleEndianBytes().toByteString())
    }

    override fun finalizeNow() {
        if (open) socket?.send(SonioxProtocol.FINALIZE)
    }

    override fun keepAlive() {
        if (open) socket?.send(SonioxProtocol.KEEPALIVE)
    }

    override fun pendingBytes(): Long = socket?.queueSize() ?: 0

    override suspend fun stop() {
        val ws = socket ?: return
        closedByUs.set(true)
        if (open) {
            ws.send(SonioxProtocol.END_OF_AUDIO)
            withTimeoutOrNull(1_500) { finished?.await() }
        }
        ws.close(1000, "client stop")
        open = false
        socket = null
    }
}
