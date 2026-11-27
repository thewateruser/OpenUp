package org.openup.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.openup.container
import org.openup.data.model.SpecialType
import org.openup.ui.apps.AppDetailScreen
import org.openup.ui.apps.AppsScreen
import org.openup.ui.folders.FoldersScreen
import org.openup.ui.home.HomeScreen
import org.openup.ui.progress.ProgressScreen
import org.openup.ui.schedule.ScheduleScreen
import org.openup.ui.settings.SettingsScreen
import org.openup.ui.special.SpecialScreen
import org.openup.ui.theme.OpenUpTheme

object Routes {
    const val HOME = "home"
    const val APPS = "apps"
    const val APP = "app/{pkg}"
    const val SPECIAL = "special/{type}"
    const val PROGRESS = "progress"
    const val SCHEDULE = "schedule"
    const val SETTINGS = "settings"
    const val FOLDERS = "folders"

    fun app(pkg: String) = "app/$pkg"
    fun special(type: SpecialType) = "special/${type.name}"
}

class MainActivity : ComponentActivity() {
    private var showProgressRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_SHOW_PROGRESS, false) == true) showProgressRequests++
        val c = container
        c.schedule.apply()
        setContent {
            val settings by c.settings.state.collectAsStateWithLifecycle()
            OpenUpTheme(settings.theme, settings.dynamicColor) {
                val nav = rememberNavController()
                LaunchedEffect(showProgressRequests) {
                    if (showProgressRequests > 0 && c.jobs.state.value != null) {
                        nav.navigate(Routes.PROGRESS) { launchSingleTop = true }
                    }
                }
                NavHost(navController = nav, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        HomeScreen(
                            onOpenApps = { nav.navigate(Routes.APPS) },
                            onOpenSpecial = { nav.navigate(Routes.special(it)) },
                            onOpenProgress = { nav.navigate(Routes.PROGRESS) { launchSingleTop = true } },
                            onOpenSchedule = { nav.navigate(Routes.SCHEDULE) },
                            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                            onOpenFolders = { nav.navigate(Routes.FOLDERS) },
                        )
                    }
                    composable(Routes.APPS) {
                        AppsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenApp = { nav.navigate(Routes.app(it)) },
                            onJobStarted = { nav.navigate(Routes.PROGRESS) { launchSingleTop = true } },
                        )
                    }
                    composable(Routes.APP, arguments = listOf(navArgument("pkg") { type = NavType.StringType })) { entry ->
                        AppDetailScreen(
                            packageName = entry.arguments?.getString("pkg").orEmpty(),
                            onBack = { nav.popBackStack() },
                            onJobStarted = { nav.navigate(Routes.PROGRESS) { launchSingleTop = true } },
                        )
                    }
                    composable(Routes.SPECIAL, arguments = listOf(navArgument("type") { type = NavType.StringType })) { entry ->
                        val type = runCatching { SpecialType.valueOf(entry.arguments?.getString("type").orEmpty()) }
                            .getOrDefault(SpecialType.MESSAGES)
                        SpecialScreen(
                            type = type,
                            onBack = { nav.popBackStack() },
                            onJobStarted = { nav.navigate(Routes.PROGRESS) { launchSingleTop = true } },
                        )
                    }
                    composable(Routes.FOLDERS) {
                        FoldersScreen(
                            onBack = { nav.popBackStack() },
                            onJobStarted = { nav.navigate(Routes.PROGRESS) { launchSingleTop = true } },
                        )
                    }
                    composable(Routes.PROGRESS) { ProgressScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.SCHEDULE) { ScheduleScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_PROGRESS, false)) showProgressRequests++
    }

    override fun onResume() {
        super.onResume()
        container.shizuku.refresh()
    }

    companion object {
        const val EXTRA_SHOW_PROGRESS = "show_progress"
    }
}
