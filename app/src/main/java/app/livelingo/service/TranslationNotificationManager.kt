package app.livelingo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.livelingo.R
import app.livelingo.core.Languages
import app.livelingo.core.SessionStatus
import app.livelingo.core.TranslationState
import app.livelingo.ui.MainActivity

/** The persistent notification shown for as long as audio is being captured. */
class TranslationNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "live_translation"
        const val NOTIFICATION_ID = 4201
    }

    fun createChannel() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun serviceIntent(action: String, req: Int): PendingIntent =
        PendingIntent.getService(
            context, req,
            Intent(context, TranslationForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openApp(route: String?, req: Int): PendingIntent =
        PendingIntent.getActivity(
            context, req,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (route != null) putExtra(MainActivity.EXTRA_ROUTE, route) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun build(s: SessionStatus): Notification {
        val pair = "${(s.detectedLanguage ?: s.sourceLanguage)?.let { Languages.nameOf(it) } ?: "Auto-detect"} → ${Languages.nameOf(s.targetLanguage)}"
        val text = when (s.state) {
            TranslationState.TRANSLATING -> "TRANSLATING: $pair"
            TranslationState.PAUSED -> "PAUSED: $pair"
            TranslationState.STARTING -> "Starting: $pair"
            TranslationState.ERROR -> s.error?.message ?: "Needs attention"
            TranslationState.OFF -> "Off"
        }
        val detail = buildString {
            append("Listening to: ").append(s.capture.label)
            s.error?.takeIf { s.state != TranslationState.ERROR }?.let { append("\n").append(it.message) }
            s.notice?.let { append("\n").append(it) }
        }
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("LiveLingo")
            .setContentText(text)
            .setSubText(s.capture.label)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$detail"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(null, 10))

        if (s.state == TranslationState.PAUSED) {
            b.addAction(0, "Resume", serviceIntent(ServiceController.ACTION_RESUME, 1))
        } else {
            b.addAction(0, "Pause", serviceIntent(ServiceController.ACTION_PAUSE, 2))
        }
        b.addAction(0, "Stop", serviceIntent(ServiceController.ACTION_STOP, 3))
        b.addAction(0, "Settings", openApp("subtitles", 11))
        return b.build()
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun update(s: SessionStatus) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(NOTIFICATION_ID, build(s))
        } catch (_: SecurityException) {
            // Notification permission revoked while running; the service keeps working.
        }
    }
}
