package com.ninetyhd.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ninetyhd.app.ui.screens.ConnectScreen
import com.ninetyhd.app.ui.screens.CreateReminderScreen
import com.ninetyhd.app.ui.screens.PermissionsScreen
import com.ninetyhd.app.ui.screens.hasRequiredPermissions
import androidx.compose.ui.platform.LocalContext
import com.ninetyhd.app.ui.screens.ReminderListScreen
import com.ninetyhd.app.ui.screens.SettingsScreen

sealed class Screen(val route: String) {
    data object Permissions : Screen("permissions")
    data object Connect : Screen("connect")
    data object ReminderList : Screen("reminders")
    data object CreateReminder : Screen("create_reminder")
    data object EditReminder : Screen("edit_reminder/{id}") {
        fun withId(id: String) = "edit_reminder/$id"
    }
    data object Settings : Screen("settings")
}

@Composable
fun NinetyHDNavHost(openNewReminder: Boolean = false) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val startRoute = if (hasRequiredPermissions(context)) Screen.ReminderList.route else Screen.Permissions.route

    // Local-first: the app always opens to the reminder list. No server required.
    NavHost(navController = navController, startDestination = startRoute) {
        composable(Screen.Permissions.route) {
            PermissionsScreen(onAllGranted = {
                navController.navigate(Screen.ReminderList.route) {
                    popUpTo(Screen.Permissions.route) { inclusive = true }
                }
            })
        }
        composable(Screen.Connect.route) {
            ConnectScreen(
                onConnected = {
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.ReminderList.route) {
            ReminderListScreen(
                onCreateNew = { navController.navigate(Screen.CreateReminder.route) },
                onEdit = { id -> navController.navigate(Screen.EditReminder.withId(id)) },
                onSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(Screen.CreateReminder.route) {
            CreateReminderScreen(
                reminderId = null,
                onSaved = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(Screen.EditReminder.route) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")
            CreateReminderScreen(
                reminderId = id,
                onSaved = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onConnect = { navController.navigate(Screen.Connect.route) },
                onDisconnect = {
                    // Stay on Settings; it will reflect the disconnected state.
                }
            )
        }
    }

    // If launched from the persistent "quick add" notification, jump to the create screen.
    LaunchedEffect(openNewReminder) {
        if (openNewReminder) {
            navController.navigate(Screen.CreateReminder.route)
        }
    }
}
