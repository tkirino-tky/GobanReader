package com.github.tkirino.gobanreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
fun App(
    viewModel: MainViewModel? = null,
    onCameraScreenChanged: (Boolean) -> Unit = {}
) {
    val readerViewModel: MainViewModel = viewModel ?: viewModel()
    // ★ここが重要：NavHostの外で一度だけ取得し、すべての composable から参照可能にする
    val uiState by readerViewModel.uiState.collectAsState()
    val navController = rememberNavController()

    // 現在の画面がCameraScreenかどうかをMainActivityへ通知する
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { backStackEntry ->
            val route = backStackEntry.destination.route
            // ルート文字列に "Camera" が含まれているかで判定
            val inCamera = route?.contains("Camera") == true
            onCameraScreenChanged(inCamera)
        }
    }

    NavHost(
        navController = navController,
        startDestination = Route.Settings
    ) {
        composable<Route.Settings> {
            SettingScreen(
                viewModel = readerViewModel,
                onBlackPlayerChanged = { name -> readerViewModel.updateBlackPlayer(name) },
                onWhitePlayerChanged = { name -> readerViewModel.updateWhitePlayer(name) },
                onGetGobanClick = { navController.navigate(Route.Camera) },
                onHistoryClick = { navController.navigate(Route.History) }
            )
        }
        composable<Route.Camera> {
            CameraScreen(
                viewModel = readerViewModel,
                onStartReadingClick = { file ->
                    readerViewModel.loadPhotoForAdjustment(file)
                    navController.navigate(Route.Corner)
                },
                onBackClick = { navController.popBackStack() }
            )
        }
        composable<Route.Corner> {
            // ★ここで外側の uiState を参照すれば、赤線は消えます
            val bitmap = uiState.adjustmentBitmap

            if (bitmap != null) {
                CornerScreen(
                    bitmap = bitmap,
                    initialCorners = uiState.initialCorners,
                    rawDetection = uiState.rawCorners,
                    onConfirmed = { corners ->
                        readerViewModel.processWithCorners(corners)
                        navController.navigate(Route.Display)
                    }
                )
            }
        }
        composable<Route.Display> {
            DisplayScreen(
                readerViewModel,
                onBackClick = { navController.navigate(Route.Settings) }
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
