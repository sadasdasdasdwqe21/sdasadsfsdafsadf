package app.livelingo.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.livelingo.container
import app.livelingo.core.AppError
import app.livelingo.core.AudioSourceType
import app.livelingo.core.ErrorKind
import app.livelingo.core.RecoveryAction
import app.livelingo.core.TranslationState
import app.livelingo.settings.enumOr

/**
 * Invisible activity that runs the start flow over whatever app is on screen:
 *  1. audio permission (RECORD_AUDIO, needed for both capture modes),
 *  2. notification permission (Android 13+, optional),
 *  3. overlay permission check,
 *  4. Android's capture consent dialog (internal audio) - required for every session on 14+,
 *  then hands the result to the foreground service and disappears.
 */
class CaptureActivity : ComponentActivity() {

    private var source = AudioSourceType.INTERNAL

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterAudioPermission() else {
            report(AppError(ErrorKind.MICROPHONE_PERMISSION_DENIED, "LiveLingo needs the audio permission to hear the video.", RecoveryAction.GRANT_MICROPHONE))
            finishQuietly()
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Optional: the session works without it, the notification is just hidden.
        afterNotificationPermission()
    }

    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ServiceController.start(this, AudioSourceType.INTERNAL, result.resultCode, data)
            finishQuietly()
        } else if (container.settings.settings.value.audio.autoFallbackToMicrophone && !alreadyRunning()) {
            Toast.makeText(this, "Internal audio declined. Using the microphone.", Toast.LENGTH_SHORT).show()
            ServiceController.start(this, AudioSourceType.MICROPHONE)
            finishQuietly()
        } else {
            if (!alreadyRunning()) {
                report(AppError(ErrorKind.CAPTURE_PERMISSION_DENIED, "Audio capture was declined, so translation did not start.", RecoveryAction.RESTART))
            }
            finishQuietly()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = intent.getStringExtra(ServiceController.EXTRA_SOURCE)
        source = if (requested != null) enumOr(requested, AudioSourceType.INTERNAL)
        else container.settings.settings.value.audio.preferredSource

        if (savedInstanceState != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            afterAudioPermission()
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun afterAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            afterNotificationPermission()
        }
    }

    private fun afterNotificationPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" for LiveLingo, then tap the tile again.", Toast.LENGTH_LONG).show()
            report(AppError(ErrorKind.OVERLAY_PERMISSION_DENIED, "Subtitles need the \"Display over other apps\" permission.", RecoveryAction.GRANT_OVERLAY))
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            finishQuietly()
            return
        }
        when (source) {
            AudioSourceType.MICROPHONE -> {
                ServiceController.start(this, AudioSourceType.MICROPHONE)
                finishQuietly()
            }
            AudioSourceType.INTERNAL -> requestProjection()
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val consent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Whole-display capture: audio capture is not tied to a single app window.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        runCatching { projectionConsent.launch(consent) }.onFailure {
            report(AppError(ErrorKind.CAPTURE_PERMISSION_DENIED, "This phone could not show the capture dialog.", RecoveryAction.RESTART))
            finishQuietly()
        }
    }

    private fun alreadyRunning() = container.session.status.value.isActive

    private fun report(error: AppError) {
        if (alreadyRunning()) {
            container.session.updateStatus { it.copy(notice = error.message) }
        } else {
            container.session.updateStatus { it.copy(state = TranslationState.ERROR, error = error) }
        }
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
