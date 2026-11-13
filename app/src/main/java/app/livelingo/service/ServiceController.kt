package app.livelingo.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.livelingo.core.AudioSourceType

/** Thin, typed wrapper around the intents the foreground service understands. */
object ServiceController {
    const val ACTION_START = "app.livelingo.action.START"
    const val ACTION_PAUSE = "app.livelingo.action.PAUSE"
    const val ACTION_RESUME = "app.livelingo.action.RESUME"
    const val ACTION_STOP = "app.livelingo.action.STOP"
    const val ACTION_TOGGLE_SUBTITLES = "app.livelingo.action.TOGGLE_SUBTITLES"
    const val ACTION_USE_MICROPHONE = "app.livelingo.action.USE_MICROPHONE"
    const val ACTION_SET_LOCKED = "app.livelingo.action.SET_LOCKED"
    const val ACTION_ENGINE_CHANGED = "app.livelingo.action.ENGINE_CHANGED"

    const val EXTRA_SOURCE = "source"
    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_RESULT_DATA = "result_data"
    const val EXTRA_LOCKED = "locked"

    /** Called by CaptureActivity once permissions (and capture consent, for internal audio) exist. */
    fun start(context: Context, source: AudioSourceType, resultCode: Int = 0, data: Intent? = null) {
        val i = Intent(context, TranslationForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_SOURCE, source.name)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
        if (data != null) i.putExtra(EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(context, i)
    }

    /** Sends a command to an already-running session. Ignored if no session is running. */
    fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
        val i = Intent(context, TranslationForegroundService::class.java).setAction(action).apply(extras)
        runCatching { context.startService(i) }
    }

    fun pause(context: Context) = send(context, ACTION_PAUSE)
    fun resume(context: Context) = send(context, ACTION_RESUME)
    fun stop(context: Context) = send(context, ACTION_STOP)

    /** Opens the start flow (permissions, consent) over whatever app is on screen. */
    fun launchStartFlow(context: Context, source: AudioSourceType? = null) {
        val i = Intent(context, CaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (source != null) i.putExtra(EXTRA_SOURCE, source.name)
        context.startActivity(i)
    }
}
