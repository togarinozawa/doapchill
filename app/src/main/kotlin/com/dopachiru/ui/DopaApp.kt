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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dopachiru.ui.changes.ChangeRequestScreen
import com.dopachiru.ui.dashboard.DashboardScreen
import com.dopachiru.ui.rules.RuleEditScreen
import com.dopachiru.ui.rules.RuleListScreen
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
            composable(TopLevel.Dashboard.route) { DashboardScreen() }

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

            composable(TopLevel.Settings.route) { SettingsScreen() }
        }
    }
}
