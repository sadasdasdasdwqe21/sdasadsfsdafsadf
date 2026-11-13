package app.livelingo.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.livelingo.R
import app.livelingo.container
import app.livelingo.core.Languages
import app.livelingo.core.SessionStatus
import app.livelingo.core.TranslationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile.
 *  OFF / ERROR  -> opens the start flow (Android shows its capture consent dialog over the
 *                  current app; a session can't legally start without it on Android 14+).
 *  TRANSLATING  -> pause.   PAUSED -> resume.   STARTING -> pause.
 * Long-press opens the app (QS_TILE_PREFERENCES).
 */
class TranslationQuickSettingsTile : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watcher?.cancel()
        watcher = scope.launch { container.session.status.collect { render(it) } }
    }

    override fun onStopListening() {
        watcher?.cancel()
        watcher = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        when (container.session.status.value.state) {
            TranslationState.OFF, TranslationState.ERROR -> {
                if (isLocked) unlockAndRun { launchStartFlow() } else launchStartFlow()
            }
            TranslationState.TRANSLATING, TranslationState.STARTING -> ServiceController.pause(this)
            TranslationState.PAUSED -> ServiceController.resume(this)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchStartFlow() {
        val intent = Intent(this, CaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(s: SessionStatus) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.label = "LiveLingo"
        val src = s.detectedLanguage ?: s.sourceLanguage
        val pair = "${if (src != null) Languages.shortLabel(src) else "AUTO"}→${Languages.shortLabel(s.targetLanguage)}"
        val (state, subtitle) = when (s.state) {
            TranslationState.OFF -> Tile.STATE_INACTIVE to "Off"
            TranslationState.STARTING -> Tile.STATE_ACTIVE to "Starting…"
            TranslationState.TRANSLATING -> Tile.STATE_ACTIVE to "Translating $pair"
            TranslationState.PAUSED -> Tile.STATE_INACTIVE to "Paused · tap to resume"
            TranslationState.ERROR -> Tile.STATE_INACTIVE to "Error · tap to retry"
        }
        tile.state = state
        tile.subtitle = subtitle
        tile.contentDescription = "LiveLingo, $subtitle"
        tile.updateTile()
    }
}
