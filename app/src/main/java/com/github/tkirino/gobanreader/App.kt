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
            // ViewModel からロード済みの Bitmap と検出結果を取得
            val bitmap = readerViewModel.adjustmentBitmap
            val detection = readerViewModel.lastDetectionResult

            if (bitmap != null && detection != null) {
                CornerScreen(
                    bitmap = bitmap,
                    initialDetection = detection,
                    onConfirmed = { corners ->
                        // 3. 確定した座標で処理を実行して結果画面へ
                        readerViewModel.processWithCorners(corners)
                        navController.navigate(Route.Display)
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