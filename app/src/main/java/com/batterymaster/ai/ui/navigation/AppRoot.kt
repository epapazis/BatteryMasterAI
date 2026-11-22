package com.batterymaster.ai.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.batterymaster.ai.R
import com.batterymaster.ai.ui.analytics.AnalyticsScreen
import com.batterymaster.ai.ui.apps.AppsScreen
import com.batterymaster.ai.ui.chargertest.ChargerTestScreen
import com.batterymaster.ai.ui.doctor.DoctorScreen
import com.batterymaster.ai.ui.health.HealthScreen
import com.batterymaster.ai.ui.home.HomeScreen
import com.batterymaster.ai.ui.power.PowerScreen
import com.batterymaster.ai.ui.power.SessionDetailScreen
import com.batterymaster.ai.ui.settings.SettingsScreen

private enum class TopLevel(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    HOME("home", R.string.nav_home, Icons.Outlined.Home),
    HEALTH("health", R.string.nav_health, Icons.Outlined.Favorite),
    POWER("power", R.string.nav_power, Icons.Outlined.BatteryChargingFull),
    ANALYTICS("analytics", R.string.nav_analytics, Icons.Outlined.Insights),
    DOCTOR("doctor", R.string.nav_doctor, Icons.Outlined.Psychology),
}

private const val SETTINGS = "settings"
private const val SESSION = "session/{id}"
private const val APPS = "apps"
private const val CHARGER_TEST = "charger_test"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val top = TopLevel.entries.firstOrNull { it.route == route }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            top != null -> stringResource(top.label)
                            route == SETTINGS -> stringResource(R.string.nav_settings)
                            route == SESSION -> stringResource(R.string.session_details)
                            route == APPS -> stringResource(R.string.apps_title)
                            route == CHARGER_TEST -> stringResource(R.string.charger_test_title)
                            else -> stringResource(R.string.app_name)
                        },
                    )
                },
                navigationIcon = {
                    if (top == null && route != null) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (top != null) {
                        IconButton(onClick = { nav.navigate(SETTINGS) }) {
                            Icon(Icons.Outlined.Settings, stringResource(R.string.nav_settings))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (top != null) {
                NavigationBar {
                    TopLevel.entries.forEach { dest ->
                        val selected = entry?.destination?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigateTopLevel(dest.route) },
                            icon = { Icon(dest.icon, null) },
                            label = { Text(stringResource(dest.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = TopLevel.HOME.route) {
            composable(TopLevel.HOME.route) {
                HomeScreen(
                    padding = padding,
                    onOpenDoctor = { nav.navigateTopLevel(TopLevel.DOCTOR.route) },
                    onOpenAnalytics = { nav.navigateTopLevel(TopLevel.ANALYTICS.route) },
                    onOpenSettings = { nav.navigate(SETTINGS) },
                    onOpenApps = { nav.navigate(APPS) },
                    onOpenChargerTest = { nav.navigate(CHARGER_TEST) },
                )
            }
            composable(TopLevel.HEALTH.route) { HealthScreen(padding) }
            composable(TopLevel.POWER.route) { PowerScreen(padding, onOpenSession = { nav.navigate("session/$it") }) }
            composable(TopLevel.ANALYTICS.route) { AnalyticsScreen(padding) }
            composable(TopLevel.DOCTOR.route) { DoctorScreen(padding) }
            composable(SETTINGS) { SettingsScreen(padding) }
            composable(APPS) { AppsScreen(padding) }
            composable(CHARGER_TEST) { ChargerTestScreen(padding) }
            composable(SESSION, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                SessionDetailScreen(padding)
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
