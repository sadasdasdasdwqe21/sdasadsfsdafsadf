package app.obdforge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.obdforge.MainActivity
import app.obdforge.ObdForgeApp
import app.obdforge.R
import app.obdforge.connection.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the adapter connection and recording alive while the app is in
 * the background (Android otherwise stops background work). It shows a persistent notification
 * with the link status and a Stop action, as Android requires.
 */
class ObdService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL = "link"
        const val ID = 1001
        const val ACTION_STOP = "app.obdforge.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, ObdService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ObdService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Adapter connection", NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, ID, build("Starting…", null), type)
        } catch (e: Exception) {
            stopSelf(); return
        }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "obdforge:link").apply {
            setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
        }
        val g = (application as ObdForgeApp).graph
        scope.launch {
            combine(g.connection.state, g.recorder.state) { link, rec -> link to rec }.collect { (link, rec) ->
                val text = when (link) {
                    is LinkState.Connected -> "Connected · ${link.target.label}" + if (rec != null) " · Recording ${rec.samples} samples" else ""
                    is LinkState.Reconnecting -> "Reconnecting (attempt ${link.attempt})…"
                    is LinkState.Connecting -> link.step
                    is LinkState.Failed -> "Connection failed"
                    LinkState.Idle -> "Not connected"
                }
                nm.notify(ID, build(text, rec?.title))
                if (link is LinkState.Idle || link is LinkState.Failed) if (rec == null) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val g = (application as ObdForgeApp).graph
            scope.launch { g.recorder.stop(); g.connection.disconnect(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, sub: String?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ObdService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setContentTitle("OBD Forge")
            .setContentText(text)
            .setSubText(sub)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Disconnect", stop)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }
}
