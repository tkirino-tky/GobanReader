package com.github.tkirino.gobanreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.tkirino.gobanreader.camera.CameraScreen
import com.github.tkirino.gobanreader.corner.CornerScreen
import com.github.tkirino.gobanreader.display.DisplayScreen
import com.github.tkirino.gobanreader.setting.SettingScreen
import kotlinx.serialization.Serializable

@Composable
fun App() {
    val readerViewModel: MainViewModel = viewModel()
    val readerUiState by readerViewModel.uiState.collectAsState()

    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Route.Settings
    ) {
        composable<Route.Settings> {
            SettingScreen(
                viewModel = readerViewModel,
                onBlackPlayerChanged = { name -> readerViewModel.updateBlackPlayer(name) },
                onWhitePlayerChanged = { name -> readerViewModel.updateWhitePlayer(name) },
                onGetGobanClick = {
                    navController.navigate(Route.Camera)
                },
                onHistoryClick = {
                    navController.navigate(Route.History)
                }
            )
        }
        composable<Route.Camera> {
            CameraScreen(
                onStartReadingClick = { file ->
                    // 1. ビットマップを ViewModel にロード（ViewModelに実装が必要です）
                    readerViewModel.loadPhotoForAdjustment(file)
                    // 2. 調整画面へ遷移
                    navController.navigate(Route.Corner)
                },
                onBackClick = { navController.popBackStack() }
            )
        }
        composable<Route.Corner> {
            val readerViewModel: MainViewModel = viewModel()
            val bitmap = readerViewModel.adjustmentBitmap
            // 前日までの lastDetectionResult は保持しつつ、新しいプロパティを使用

            if (bitmap != null) {
                CornerScreen(
                    bitmap = bitmap,
                    initialDetection = readerViewModel.initialCorners,
                    rawDetection = readerViewModel.rawCorners,
                    onConfirmed = { corners ->
                        readerViewModel.processWithCorners(corners)
                        navController.navigate(route = "display_route")
                    }
                )
            }
        }
        composable<Route.Display> {
            DisplayScreen(
                readerViewModel,
                onBackClick = {
                    navController.navigate(Route.Settings)
                }
            )
        }
    }
}

object Route {
    @Serializable
    data object Settings
    @Serializable
    data object History
    @Serializable
    data object Camera
    @Serializable
    data object Corner
    @Serializable
    data object Display
}