package com.github.tkirino.gobanreader.display

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tkirino.gobanreader.MainViewModel
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayScreen(
    viewModel: MainViewModel, // 【追加】ViewModel を引数で受け取る
    onBackClick: () -> Unit
) {
    // 【ここを追加】スマートフォンの Context を取得する
    val context = LocalContext.current

    // ここから貼り付け】ViewModel のメッセージを監視して Toast を出す仕組み
    LaunchedEffect(viewModel.toastMessage) {
        viewModel.toastMessage?.let { message ->
            // 画面下部にフワッとメッセージを表示する（Android標準機能）
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

            // 表示し終わったら、メッセージを空（通知済み）に戻す
            viewModel.toastMessage = null
        }
    }

    // 【追加】UiState を Compose の状態として監視（リアルタイム連動）
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 画面が開いたときにダミーデータをロードする
    LaunchedEffect(Unit) {
        viewModel.loadDummySgf()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Goban Reader - Reading") })
        }
       ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .navigationBarsPadding()
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. 上部：説明表示エリア
            // 【変更】uiState の中身（gameRecord）をテキストとして表示してみるテスト
            Text(
                text = if (uiState.gameRecord != null) {
                    "SGF読み込み成功！\n配置データ: ${uiState.gameRecord}"
                } else {
                    "データを読み込み中..."
                },
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2. 中央：碁盤
            // DisplayScreen.kt の中で
            //
            //       if (viewModel.debugWarpedBoard != null) {
            //
            //            Image(
            //                bitmap = viewModel.debugWarpedBoard!!.asImageBitmap(),
            //                contentDescription = "デバッグ用：切り出した盤面"
            //            )
            //        } else {
                    GoBoard(
                        boardMatrix = uiState.boardLayout, // uiStateのboardLayoutを渡す
                        modifier = Modifier.fillMaxWidth()
                    )
            //  }


            Spacer(modifier = Modifier.weight(1f))

            // 3. 下部：操作ボタン群
            // 3. 下部：操作ボタン群
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 【修正】波括弧の中に viewModel のメソッドを配置します
                Button(onClick = { viewModel.rotateLeft() }) {
                    Text("左90°回転")
                }

                // 【修正】波括弧の中に viewModel のメソッドを配置します
                Button(onClick = { viewModel.rotateRight() }) {
                    Text("右90°回転")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = {
                    // ViewModelの関数を呼び出し、取得した context と現在の gameRecord を渡す
                    viewModel.exportSgf(context, uiState.gameRecord)
                }) {
                    Text("SGF出力")
                }
                Button(onClick = onBackClick) { Text("戻る") }
            }
        }
    }
}
