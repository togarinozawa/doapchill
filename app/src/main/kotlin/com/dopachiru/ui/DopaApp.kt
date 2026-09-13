package com.dopachiru.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.dopachiru.ui.dev.DevToolsScreen
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dopachiru.ui.changes.ChangeRequestScreen
import com.dopachiru.ui.dashboard.DashboardScreen
import com.dopachiru.ui.reservation.ReservationScreen
import com.dopachiru.ui.rules.RuleEditScreen
import com.dopachiru.ui.rules.RuleListScreen
import com.dopachiru.ui.settings.SettingsPage
import com.dopachiru.ui.settings.SettingsPageScreen
import com.dopachiru.ui.settings.SettingsScreen
import com.dopachiru.ui.tags.TagScreen

private enum class TopLevel(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Dashboard("dashboard", "記録", Icons.Filled.Insights),
    Rules("rules", "ルール", Icons.Filled.Block),
    Tags("tags", "タグ", Icons.Filled.Label),
    Changes("changes", "変更", Icons.Filled.History),
    Settings("settings", "設定", Icons.Filled.Settings),
}

/** 設定の一覧そのものの経路。入れ子の図の始点。 */
private const val SETTINGS_INDEX = "settings/index"

@Composable
fun DopaApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevel.entries.forEach { item ->
                    val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevel.Dashboard.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopLevel.Dashboard.route) {
                DashboardScreen(onOpenReservations = { navController.navigate("reservations") })
            }

            composable("reservations") { ReservationScreen() }

            composable(TopLevel.Rules.route) {
                RuleListScreen(
                    onCreate = { navController.navigate("rule/0") },
                    onEdit = { id -> navController.navigate("rule/$id") },
                )
            }

            composable("rule/{ruleId}") { entry ->
                val ruleId = entry.arguments?.getString("ruleId")?.toLongOrNull() ?: 0L
                RuleEditScreen(
                    ruleId = ruleId,
                    onDone = { navController.popBackStack() },
                )
            }

            composable(TopLevel.Tags.route) { TagScreen() }

            composable(TopLevel.Changes.route) { ChangeRequestScreen() }

            // 設定は入れ子の図にしてある。こうしておくと、下の段の中に居ても
            // 下タブの「設定」が選ばれたままになり、タブを押し直せば見ていたページに戻る
            navigation(
                route = TopLevel.Settings.route,
                startDestination = SETTINGS_INDEX,
            ) {
                composable(SETTINGS_INDEX) {
                    SettingsScreen(
                        onOpen = { page -> navController.navigate("settings/page/" + page.id) },
                    )
                }

                composable("settings/page/{pageId}") { entry ->
                    val page = SettingsPage.of(entry.arguments?.getString("pageId"))
                    if (page == null) {
                        // 知らないページ名。黙って白い画面を見せるより、一覧に戻す
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        SettingsPageScreen(
                            page = page,
                            onBack = { navController.popBackStack() },
                            onOpenDevTools = { navController.navigate("dev") },
                        )
                    }
                }
            }

            // 開発用。設定の一番下からコードを入れたときだけ辿り着ける
            composable("dev") { DevToolsScreen() }
        }
    }
}
