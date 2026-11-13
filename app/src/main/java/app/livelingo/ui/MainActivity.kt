package app.livelingo.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.livelingo.ui.components.ScreenTopBar
import app.livelingo.ui.screens.AudioScreen
import app.livelingo.ui.screens.DiagnosticsScreen
import app.livelingo.ui.screens.EngineScreen
import app.livelingo.ui.screens.HistoryScreen
import app.livelingo.ui.screens.HomeScreen
import app.livelingo.ui.screens.LanguageScreen
import app.livelingo.ui.screens.SubtitleScreen
import app.livelingo.ui.theme.Brand
import app.livelingo.ui.theme.LiveLingoTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ROUTE = "route"
    }

    private val vm: MainViewModel by viewModels()
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            LiveLingoTheme {
                LiveLingoApp(vm, pendingRoute.value) { pendingRoute.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Rounded.Home),
    Tab("languages", "Languages", Icons.Rounded.Translate),
    Tab("subtitles", "Subtitles", Icons.Rounded.Subtitles),
    Tab("history", "History", Icons.Rounded.History),
)

private val titles = mapOf(
    "languages" to "Languages",
    "subtitles" to "Subtitle style",
    "history" to "History",
    "audio" to "Audio capture",
    "engine" to "Speech & translation",
    "diagnostics" to "Diagnostics",
)

@Composable
private fun LiveLingoApp(vm: MainViewModel, route: String?, onRouteHandled: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: "home"

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshSetup() }

    LaunchedEffect(route) {
        if (route != null && route in titles.keys) {
            nav.navigate(route) { launchSingleTop = true }
            onRouteHandled()
        }
    }

    fun go(r: String) {
        if (tabs.any { it.route == r }) {
            nav.navigate(r) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        } else {
            nav.navigate(r) { launchSingleTop = true }
        }
    }

    val isTab = tabs.any { it.route == current }
    Scaffold(
        containerColor = Brand.Background,
        topBar = {
            if (current != "home") ScreenTopBar(titles[current] ?: "", onBack = if (isTab) null else ({ nav.popBackStack(); Unit }))
        },
        bottomBar = {
            if (isTab) {
                NavigationBar(containerColor = Brand.Surface) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = { go(t.route) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Brand.TealDim),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = "home") {
                composable("home") { HomeScreen(vm) { go(it) } }
                composable("languages") { LanguageScreen(vm) }
                composable("subtitles") { SubtitleScreen(vm) }
                composable("history") { HistoryScreen(vm) }
                composable("audio") { AudioScreen(vm) }
                composable("engine") { EngineScreen(vm) }
                composable("diagnostics") { DiagnosticsScreen(vm) }
            }
        }
    }
}
