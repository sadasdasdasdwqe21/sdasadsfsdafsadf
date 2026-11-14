package app.obdforge

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.obdforge.connection.LinkState
import app.obdforge.ui.AppRoot
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val g = (application as ObdForgeApp).graph
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        lifecycleScope.launch {
            combine(g.settings.state, g.connection.state) { s, c -> s.keepScreenOn && (c is LinkState.Connected || c is LinkState.Reconnecting) }.collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        if (savedInstanceState == null && g.settings.value.autoConnectOnStart && g.connection.state.value is LinkState.Idle) g.connection.reconnectLast()
        setContent { AppRoot() }
    }
}
