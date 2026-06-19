package com.github.tkirino.gobanreader.ui

import kotlinx.serialization.Serializable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.tkirino.gobanreader.ReaderViewModel

// 対局情報入力画面　ーーーーーーーーー＞　カメラ撮影・画像選択画面
// (Settings)     onGetGobanClick   (GobanReader)
//  onHistoryClick                  onStartReadingClick
//　　　　｜　　　　　　　　　　　          　｜
//　　　　V　　　　　　　　　　　　           V
//　　　履歴一覧　　　　　　　　　　　　　　　読み込みの結果を表示
//     (History)			　　　　　　　　(ReadResult)
//                       　　　　　　　　 onWriteSGFClick
//　　　　　　　　　　　　　　　　　　　　　　　　｜
//　　　　　　　　　　　　　　　　　　　　　　　　V
//　　　　　　　　　　　　　　　　 　　　　　SGF出力
//                           　　　　　(ToSGFFile)

@Composable
fun App() {
    val readerViewModel: ReaderViewModel = viewModel() // valが必要、末尾の)は不要
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
                   navController.navigate(Route.GobanReader)
                },
                onHistoryClick = {
                   navController.navigate(Route.History)
                }
            )
        }
        composable<Route.GobanReader> {
            GobanReaderScreen(
                onStartReadingClick = {
                    navController.navigate(Route.ReadResult)
                },
                onBackClick = {
                    navController.navigate(Route.Settings)
                }
            )
        }
        composable<Route.ReadResult> {
            ReadResultScreen(
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
    data object GobanReader
    @Serializable
    data object ReadResult
}