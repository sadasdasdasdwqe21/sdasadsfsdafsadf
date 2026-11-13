package app.livelingo.service

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import app.livelingo.AppContainer
import app.livelingo.audio.AudioCaptureProvider
import app.livelingo.audio.CaptureException
import app.livelingo.audio.InternalAudioCapture
import app.livelingo.audio.MicrophoneCapture
import app.livelingo.container
import app.livelingo.core.AppError
import app.livelingo.core.AudioSourceType
import app.livelingo.core.CaptureStatus
import app.livelingo.core.ErrorKind
import app.livelingo.core.Languages
import app.livelingo.core.RecoveryAction
import app.livelingo.core.TranslationState
import app.livelingo.overlay.OverlayActions
import app.livelingo.overlay.SubtitleOverlayManager
import app.livelingo.pipeline.PipelineListener
import app.livelingo.pipeline.TranslationPipeline
import app.livelingo.settings.enumOr
import app.livelingo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Runs the live translation session while the user is in other apps.
 *
 * Foreground service types: mediaProjection (internal audio) and microphone. Android 14+
 * requires the capture consent to exist before this service enters the foreground with the
 * mediaProjection type, which is why sessions always begin in CaptureActivity.
 */
class TranslationForegroundService : Service(), OverlayActions, PipelineListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: AppContainer
    private lateinit var notifier: TranslationNotificationManager

    private var pipeline: TranslationPipeline? = null
    private var overlay: SubtitleOverlayManager? = null
    private var projection: MediaProjection? = null
    private var running = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        c = container
        notifier = TranslationNotificationManager(this)
        notifier.createChannel()

        scope.launch {
            c.session.status
                .map { Triple(it.state, it.languageLine() + it.capture + it.sourceType, (it.error?.message ?: "") + (it.notice ?: "")) }
                .distinctUntilChanged()
                .collect {
                    if (running) notifier.update(c.session.status.value)
                    TileService.requestListeningState(this@TranslationForegroundService, ComponentName(this@TranslationForegroundService, TranslationQuickSettingsTile::class.java))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ServiceController.ACTION_START -> handleStart(intent)
            ServiceController.ACTION_PAUSE -> pause()
            ServiceController.ACTION_RESUME -> resume()
            ServiceController.ACTION_STOP -> stop()
            ServiceController.ACTION_TOGGLE_SUBTITLES -> setSubtitlesVisible(!c.session.status.value.subtitlesVisible)
            ServiceController.ACTION_USE_MICROPHONE -> switchSource(AudioSourceType.MICROPHONE)
            ServiceController.ACTION_SET_LOCKED -> setLocked(intent.getBooleanExtra(ServiceController.EXTRA_LOCKED, true))
            ServiceController.ACTION_ENGINE_CHANGED -> pipeline?.restartEngine()
        }
        if (!running && intent?.action != ServiceController.ACTION_START) stopSelf(startId)
        return START_NOT_STICKY
    }

    // --- Starting ------------------------------------------------------------------------------

    private fun handleStart(intent: Intent) {
        val source = enumOr(intent.getStringExtra(ServiceController.EXTRA_SOURCE), AudioSourceType.MICROPHONE)
        val resultCode = intent.getIntExtra(ServiceController.EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, ServiceController.EXTRA_RESULT_DATA, Intent::class.java)

        // Enter the foreground immediately (required within seconds of startForegroundService).
        if (!enterForeground(source)) return

        if (running) {
            // Already translating: this start carries a new projection for switching to internal audio.
            if (source == AudioSourceType.INTERNAL && data != null) {
                val capture = createInternalCapture(resultCode, data) ?: return
                runCatching { pipeline?.switchCapture(capture) }.onFailure { onCaptureFailed(it.message ?: "Could not start internal audio") }
                c.session.updateStatus { it.copy(notice = "Switched to internal audio.") }
            }
            return
        }

        running = true
        stopping = false
        val settings = c.settings.settings.value
        c.session.reset(settings.language.targetLanguage)
        c.session.updateStatus {
            it.copy(
                state = TranslationState.STARTING,
                sourceType = source,
                sourceLanguage = settings.language.effectiveSource,
                targetLanguage = settings.language.targetLanguage,
                capture = CaptureStatus.WAITING_FOR_AUDIO,
            )
        }
        notifier.update(c.session.status.value)
        scope.launch {
            c.settings.setSessionActive(true)
            c.settings.updateLanguage { it.withRecent(it.pairKey) }
        }

        val capture: AudioCaptureProvider = when (source) {
            AudioSourceType.INTERNAL -> createInternalCapture(resultCode, data)
            AudioSourceType.MICROPHONE -> MicrophoneCapture(this, settings.audio.noiseSuppression, settings.audio.autoGain)
        } ?: return

        val ov = SubtitleOverlayManager(this, c, scope, this)
        overlay = ov
        if (!ov.show()) {
            c.session.updateStatus {
                it.copy(error = AppError(ErrorKind.OVERLAY_PERMISSION_DENIED, "Subtitles can't be shown: allow \"Display over other apps\" for LiveLingo.", RecoveryAction.GRANT_OVERLAY))
            }
        }

        val p = TranslationPipeline(this, c, scope, this)
        pipeline = p
        try {
            p.start(capture)
        } catch (e: CaptureException) {
            if (source == AudioSourceType.INTERNAL && settings.audio.autoFallbackToMicrophone) {
                switchToMicrophone("Internal audio could not start (${e.message}). Using the microphone instead.")
            } else {
                fail(AppError(ErrorKind.AUDIO_STREAM_INTERRUPTED, e.message ?: "Audio capture failed.", RecoveryAction.RESTART))
            }
        }
    }

    private fun enterForeground(source: AudioSourceType): Boolean {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (source == AudioSourceType.INTERNAL || projection != null) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return try {
            ServiceCompat.startForeground(this, TranslationNotificationManager.NOTIFICATION_ID, notifier.build(c.session.status.value.copy(state = TranslationState.STARTING)), types)
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException: Android refused.
            c.session.updateStatus {
                it.copy(
                    state = TranslationState.ERROR,
                    error = AppError(ErrorKind.SERVICE_STOPPED, "Android did not allow LiveLingo to start capturing (${e.javaClass.simpleName}). Open the app and start from there.", RecoveryAction.RESTART),
                )
            }
            stopSelf()
            false
        }
    }

    private fun createInternalCapture(resultCode: Int, data: Intent?): AudioCaptureProvider? {
        if (data == null) {
            fail(AppError(ErrorKind.CAPTURE_PERMISSION_DENIED, "Permission to capture audio was not given.", RecoveryAction.RESTART))
            return null
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            fail(AppError(ErrorKind.CAPTURE_PERMISSION_DENIED, "Android did not grant audio capture. Tap the tile and choose \"Start now\".", RecoveryAction.RESTART))
            return null
        }
        projection?.let { old -> if (old !== mp) runCatching { old.stop() } }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection === mp) {
                    projection = null
                    onProjectionStopped()
                }
            }
        }, Handler(Looper.getMainLooper()))
        return InternalAudioCapture(this, mp)
    }

    private fun onProjectionStopped() {
        if (!running || stopping) return
        if (pipeline?.sourceType != AudioSourceType.INTERNAL) return
        if (c.settings.settings.value.audio.autoFallbackToMicrophone) {
            switchToMicrophone("Audio sharing was stopped by the system. Switched to the microphone.")
        } else {
            fail(AppError(ErrorKind.AUDIO_STREAM_INTERRUPTED, "Audio sharing was stopped by the system.", RecoveryAction.RESTART))
        }
    }

    private fun switchToMicrophone(notice: String) {
        val p = pipeline ?: return
        val a = c.settings.settings.value.audio
        try {
            p.switchCapture(MicrophoneCapture(this, a.noiseSuppression, a.autoGain))
            c.session.updateStatus { it.copy(notice = notice, error = null, sourceType = AudioSourceType.MICROPHONE) }
        } catch (e: CaptureException) {
            fail(AppError(ErrorKind.MICROPHONE_PERMISSION_DENIED, e.message ?: "The microphone could not be opened.", RecoveryAction.GRANT_MICROPHONE))
        }
    }

    // --- PipelineListener -----------------------------------------------------------------------

    override fun onCaptureBlocked(reason: String) {
        scope.launch {
            if (c.settings.settings.value.audio.autoFallbackToMicrophone) {
                switchToMicrophone("$reason Switched to the microphone.")
            } else {
                c.session.updateStatus {
                    it.copy(error = AppError(ErrorKind.INTERNAL_CAPTURE_BLOCKED, reason, RecoveryAction.SWITCH_TO_MICROPHONE))
                }
            }
        }
    }

    override fun onCaptureFailed(reason: String) {
        scope.launch {
            if (pipeline?.sourceType == AudioSourceType.INTERNAL && c.settings.settings.value.audio.autoFallbackToMicrophone) {
                switchToMicrophone("$reason Switched to the microphone.")
            } else {
                fail(AppError(ErrorKind.AUDIO_STREAM_INTERRUPTED, reason, RecoveryAction.RESTART))
            }
        }
    }

    override fun onFatalError(error: AppError) {
        scope.launch { fail(error) }
    }

    // --- OverlayActions ------------------------------------------------------------------------

    override fun pause() {
        pipeline?.pause()
    }

    override fun resume() {
        pipeline?.resume()
    }

    override fun stop() = stopSession(null)

    override fun setTarget(code: String) {
        if (!Languages.isSupported(code)) return
        scope.launch {
            c.settings.updateLanguage { it.copy(targetLanguage = code).let { p -> p.withRecent(p.pairKey) } }
            c.session.updateStatus { it.copy(targetLanguage = code) }
            pipeline?.restartEngine()
        }
    }

    override fun setSubtitlesVisible(visible: Boolean) {
        c.session.updateStatus { it.copy(subtitlesVisible = visible) }
    }

    override fun setLocked(locked: Boolean) {
        scope.launch { c.settings.updateSubtitle { it.copy(locked = locked) } }
    }

    override fun switchSource(type: AudioSourceType) {
        val p = pipeline ?: return
        if (p.sourceType == type) return
        when (type) {
            AudioSourceType.MICROPHONE -> switchToMicrophone("Switched to the microphone.")
            AudioSourceType.INTERNAL -> {
                val mp = projection
                if (mp != null) {
                    runCatching { p.switchCapture(InternalAudioCapture(this, mp)) }
                        .onSuccess { c.session.updateStatus { it.copy(notice = "Switched to internal audio.", sourceType = AudioSourceType.INTERNAL) } }
                        .onFailure { c.session.updateStatus { s -> s.copy(notice = "Internal audio unavailable: ${it.message}") } }
                } else {
                    // A new capture consent is needed; the activity returns it to this service.
                    overlay?.closePanel()
                    ServiceController.launchStartFlow(this, AudioSourceType.INTERNAL)
                }
            }
        }
    }

    override fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ROUTE, "subtitles")
        )
    }

    // --- Stopping ------------------------------------------------------------------------------

    private fun fail(error: AppError) = stopSession(error)

    private fun stopSession(error: AppError?) {
        if (stopping) return
        stopping = true
        val p = pipeline
        pipeline = null
        overlay?.hide()
        overlay = null
        scope.launch {
            runCatching { p?.stop() }
            runCatching { projection?.stop() }
            projection = null
            c.settings.setSessionActive(false)
            c.session.setCaptions(app.livelingo.core.CaptionDisplay())
            c.session.updateStatus {
                it.copy(
                    state = if (error != null) TranslationState.ERROR else TranslationState.OFF,
                    capture = CaptureStatus.IDLE,
                    error = error,
                    notice = null,
                )
            }
            running = false
            ServiceCompat.stopForeground(this@TranslationForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onDestroy() {
        // Reached after stopSession, or when the system tears the service down.
        overlay?.hide()
        overlay = null
        runCatching { projection?.stop() }
        projection = null
        if (running) {
            running = false
            val p = pipeline
            pipeline = null
            CoroutineScope(Dispatchers.Default).launch { runCatching { p?.stop() } }
            c.session.updateStatus {
                it.copy(
                    state = TranslationState.ERROR,
                    capture = CaptureStatus.IDLE,
                    error = AppError(ErrorKind.SERVICE_STOPPED, "The translation service was stopped by the system.", RecoveryAction.RESTART),
                )
            }
        }
        TileService.requestListeningState(this, ComponentName(this, TranslationQuickSettingsTile::class.java))
        scope.cancel()
        super.onDestroy()
    }
}
