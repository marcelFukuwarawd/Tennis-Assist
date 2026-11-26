package com.courtvision.ai.ui

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsTennis
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.foundation.layout.WindowInsets
import com.courtvision.ai.ui.screens.FullScreenPlayer
import com.courtvision.ai.ui.screens.SessionSubScreen
import com.courtvision.ai.ui.screens.SubScreen
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.courtvision.ai.CourtVisionApp
import com.courtvision.ai.R
import com.courtvision.ai.ui.screens.BiomechanicsScreen
import com.courtvision.ai.ui.screens.CalibrationScreen
import com.courtvision.ai.ui.screens.EditorScreen
import com.courtvision.ai.ui.screens.HomeScreen
import com.courtvision.ai.ui.screens.LicensesScreen
import com.courtvision.ai.ui.screens.MatchesScreen
import com.courtvision.ai.ui.screens.PrivacyScreen
import com.courtvision.ai.ui.screens.RecordScreen
import com.courtvision.ai.ui.screens.RecordingScreen
import com.courtvision.ai.ui.screens.SessionScreen
import com.courtvision.ai.ui.screens.SettingsScreen
import com.courtvision.ai.ui.screens.StatsScreen
import com.courtvision.ai.ui.screens.ZonesScreen

fun Context.app(): CourtVisionApp = applicationContext as CourtVisionApp

@Composable
fun app(): CourtVisionApp = LocalContext.current.app()

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", R.string.tab_home, Icons.Filled.Home),
    Tab("matches", R.string.tab_matches, Icons.Filled.SportsTennis),
    Tab("settings", R.string.tab_settings, Icons.Filled.Settings),
)

/** Routes shown edge-to-edge without the bottom bar (camera, calibration, full-screen video). */
private val fullScreenRoutes = listOf("camera", "calibrate", "player")

object Routes {
    fun session(id: Long, t: Long = -1) = "session/$id?t=$t"
    fun camera(id: Long) = "camera/$id"
    fun calibrate(id: Long, live: Boolean = false) = "calibrate/$id?live=$live"
    fun editor(id: Long) = "editor/$id"
    fun biomech(id: Long, t: Long = 0) = "biomech/$id?t=$t"
    fun record(kind: String) = "record?kind=$kind"
    fun player(id: Long, t: Long = 0) = "player/$id?t=$t"
    fun map(id: Long) = "map/$id"
    fun coach(id: Long) = "coach/$id"
}

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val fullScreen = fullScreenRoutes.any { route.startsWith(it) }
    val showBar = tabs.any { route == it.route }
    Scaffold(
        contentWindowInsets = if (fullScreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route.startsWith(t.route),
                        onClick = { nav.navigateTab(t.route) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { HomeScreen(nav) }
            composable("record?kind={kind}", arguments = listOf(navArgument("kind") { type = NavType.StringType; defaultValue = "match" })) {
                RecordScreen(nav, it.arguments?.getString("kind") ?: "match")
            }
            composable("matches") { MatchesScreen(nav) }
            composable("stats") { StatsScreen(nav) }
            composable(
                "player/{id}?t={t}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("t") { type = NavType.LongType; defaultValue = 0L }),
            ) { FullScreenPlayer(nav, it.arguments!!.getLong("id"), it.arguments!!.getLong("t")) }
            composable("map/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { SessionSubScreen(nav, it.arguments!!.getLong("id"), SubScreen.MAP) }
            composable("coach/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { SessionSubScreen(nav, it.arguments!!.getLong("id"), SubScreen.COACH) }
            composable("settings") { SettingsScreen(nav) }
            composable("privacy") { PrivacyScreen(nav) }
            composable("licenses") { LicensesScreen(nav) }
            composable("zones") { ZonesScreen(nav) }
            composable("camera/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                RecordingScreen(nav, it.arguments!!.getLong("id"))
            }
            composable(
                "session/{id}?t={t}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("t") { type = NavType.LongType; defaultValue = -1L }),
            ) { SessionScreen(nav, it.arguments!!.getLong("id"), it.arguments!!.getLong("t")) }
            composable(
                "calibrate/{id}?live={live}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("live") { type = NavType.BoolType; defaultValue = false }),
            ) { CalibrationScreen(nav, it.arguments!!.getLong("id")) }
            composable("editor/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                EditorScreen(nav, it.arguments!!.getLong("id"))
            }
            composable(
                "biomech/{id}?t={t}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("t") { type = NavType.LongType; defaultValue = 0L }),
            ) { BiomechanicsScreen(nav, it.arguments!!.getLong("id"), it.arguments!!.getLong("t")) }
        }
    }
}

fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
