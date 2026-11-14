package app.obdforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.obdforge.connection.AdapterTarget
import app.obdforge.connection.LinkState
import app.obdforge.ui.components.graph
import app.obdforge.ui.screens.AlertsScreen
import app.obdforge.ui.screens.AnalysisScreen
import app.obdforge.ui.screens.AssistantScreen
import app.obdforge.ui.screens.CompareScreen
import app.obdforge.ui.screens.ConnectScreen
import app.obdforge.ui.screens.DashboardEditScreen
import app.obdforge.ui.screens.DashboardScreen
import app.obdforge.ui.screens.DashboardsScreen
import app.obdforge.ui.screens.DiagnosticsScreen
import app.obdforge.ui.screens.DtcDetailScreen
import app.obdforge.ui.screens.DtcLibraryScreen
import app.obdforge.ui.screens.HomeScreen
import app.obdforge.ui.screens.LiveGraphScreen
import app.obdforge.ui.screens.LiveListScreen
import app.obdforge.ui.screens.MoreScreen
import app.obdforge.ui.screens.PerformanceScreen
import app.obdforge.ui.screens.ProfilesScreen
import app.obdforge.ui.screens.SessionDetailScreen
import app.obdforge.ui.screens.SessionsScreen
import app.obdforge.ui.screens.SettingsScreen
import app.obdforge.ui.screens.TerminalScreen
import app.obdforge.ui.screens.VehicleEditScreen
import app.obdforge.ui.screens.VehiclesScreen
import app.obdforge.ui.theme.ObdTheme
import app.obdforge.ui.theme.Palette

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("dashboards", "Live", Icons.Filled.Speed),
    Tab("diag", "Diagnose", Icons.Filled.Build),
    Tab("sessions", "Logs", Icons.Filled.List),
    Tab("more", "More", Icons.Filled.Menu),
)

@Composable
fun AppRoot() {
    val g = graph()
    val settings by g.settings.state.collectAsStateWithLifecycle()
    ObdTheme(settings.theme) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val current = entry?.destination?.route
        val wide = LocalConfiguration.current.screenWidthDp >= 600
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(Unit) { g.alerts.events.collect { snack.showSnackbar("${it.title}: ${it.message}") } }

        fun go(route: String) {
            nav.navigate(route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true }
        }
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (wide) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEach { t -> NavigationRailItem(selected = current == t.route, onClick = { go(t.route) }, icon = { Icon(t.icon, null) }, label = { Text(t.label) }) }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                snackbarHost = { SnackbarHost(snack) },
                bottomBar = {
                    if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        tabs.forEach { t -> NavigationBarItem(selected = current == t.route, onClick = { go(t.route) }, icon = { Icon(t.icon, null) }, label = { Text(t.label) }) }
                    }
                },
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize()) {
                    LinkBanner(nav)
                    Box(Modifier.weight(1f)) { NavGraph(nav) }
                }
            }
        }
    }
}

/** Thin status strip: reconnecting, simulated data, and a moving-vehicle reminder. */
@Composable
private fun LinkBanner(nav: NavHostController) {
    val g = graph()
    val st by g.connection.state.collectAsStateWithLifecycle()
    val values by g.live.values.collectAsStateWithLifecycle()
    val speed = values["speed"]?.value ?: 0.0
    val (text, color) = when (val s = st) {
        is LinkState.Reconnecting -> "Connection lost — reconnecting (attempt ${s.attempt})…" to Palette.Amber
        is LinkState.Connected -> if (s.target is AdapterTarget.Simulator) "SIMULATOR — data is simulated, not from a vehicle" to Palette.Amber
            else if (speed > 15) "Vehicle moving — let a passenger operate the app" to Palette.Orange else null to Palette.Cyan
        else -> null to Palette.Cyan
    }
    if (text != null) {
        Text(text, color = MaterialTheme.colorScheme.background, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth().background(color).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@Composable
private fun NavGraph(nav: NavHostController) {
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(nav) }
        composable("connect") { ConnectScreen(nav) }
        composable("terminal") { TerminalScreen(nav) }
        composable("dashboards") { DashboardsScreen(nav) }
        composable("dashboard/{id}", listOf(navArgument("id") { type = NavType.LongType })) { DashboardScreen(nav, it.arguments!!.getLong("id")) }
        composable("dashboard_edit/{id}", listOf(navArgument("id") { type = NavType.LongType })) { DashboardEditScreen(nav, it.arguments!!.getLong("id")) }
        composable("live") { LiveListScreen(nav) }
        composable("livegraph?keys={keys}", listOf(navArgument("keys") { type = NavType.StringType; defaultValue = "rpm,speed" })) {
            LiveGraphScreen(nav, it.arguments?.getString("keys")?.split(",")?.filter { k -> k.isNotBlank() } ?: emptyList())
        }
        composable("diag") { DiagnosticsScreen(nav) }
        composable("dtc/{code}") { DtcDetailScreen(nav, it.arguments?.getString("code") ?: "") }
        composable("dtclib") { DtcLibraryScreen(nav) }
        composable("sessions") { SessionsScreen(nav) }
        composable("session/{id}", listOf(navArgument("id") { type = NavType.LongType })) { SessionDetailScreen(nav, it.arguments!!.getLong("id")) }
        composable("compare/{ids}") { CompareScreen(nav, it.arguments?.getString("ids")?.split(",")?.mapNotNull { s -> s.toLongOrNull() } ?: emptyList()) }
        composable("analysis?session={session}", listOf(navArgument("session") { type = NavType.LongType; defaultValue = -1L })) {
            AnalysisScreen(nav, it.arguments?.getLong("session")?.takeIf { s -> s >= 0 })
        }
        composable("perf") { PerformanceScreen(nav) }
        composable("assistant") { AssistantScreen(nav) }
        composable("alerts") { AlertsScreen(nav) }
        composable("vehicles") { VehiclesScreen(nav) }
        composable("vehicle/{id}", listOf(navArgument("id") { type = NavType.LongType })) { VehicleEditScreen(nav, it.arguments!!.getLong("id")) }
        composable("profiles") { ProfilesScreen(nav) }
        composable("settings") { SettingsScreen(nav) }
        composable("more") { MoreScreen(nav) }
    }
}
