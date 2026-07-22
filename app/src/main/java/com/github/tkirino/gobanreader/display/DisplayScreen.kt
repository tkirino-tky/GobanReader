package com.github.tkirino.gobanreader.display

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tkirino.gobanreader.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(viewModel.toastMessage) {
        viewModel.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.toastMessage = null
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // ※もし「実写の解析フロー」を通らずに直接ここに来た場合のフォールバック等が必要でなければ、
    //   この LaunchedEffect(Unit) { viewModel.loadDummySgf() } は削除またはコメントアウトします。
    //   （実写の解析結果を優先させるため、ここでは外しています）

    Scaffold(
        topBar = { TopAppBar(title = { Text("Goban Reader - 解析結果表示") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .navigationBarsPadding()
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ローディング中や、状態に応じたテキスト表示
            val statusText = if (uiState.isLoading) {
                "画像を解析中..."
            } else {
                "解析完了（白石/黒石の配置を表示中）"
            }

            Text(
                text = statusText,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().height(40.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // MainViewModel の processWithCorners 等で更新された実データの boardLayout を GoBoard に渡す
            GoBoard(
                boardMatrix = uiState.boardLayout,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { viewModel.rotateLeft() }) { Text("左90°回転") }
                Button(onClick = { viewModel.rotateRight() }) { Text("右90°回転") }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { viewModel.exportSgf(context, uiState.gameRecord) }) { Text("SGF出力") }
                Button(onClick = onBackClick) { Text("戻る") }
            }
        }
    }
}
