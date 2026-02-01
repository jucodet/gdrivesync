package com.gdrivesync.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gdrivesync.app.ui.screens.FolderSelectionScreen
import com.gdrivesync.app.ui.screens.HomeScreen
import com.gdrivesync.app.ui.screens.SettingsScreen
import com.gdrivesync.app.ui.viewmodel.SettingsViewModel

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Settings : Screen("settings")
    object FolderSelection : Screen("folder_selection")
}

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        
        composable(Screen.Settings.route) {
            val viewModel: SettingsViewModel = viewModel()
            
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToFolderSelection = {
                    navController.navigate(Screen.FolderSelection.route) {
                        // Passer le ViewModel via les arguments de navigation
                        popUpTo(Screen.Settings.route) { saveState = true }
                    }
                },
                viewModel = viewModel
            )
        }
        
        composable(Screen.FolderSelection.route) {
            val viewModel: SettingsViewModel = viewModel()
            
            FolderSelectionScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onFolderSelected = { folderId, folderName ->
                    viewModel.selectDriveFolder(folderId, folderName)
                }
            )
        }
    }
}

