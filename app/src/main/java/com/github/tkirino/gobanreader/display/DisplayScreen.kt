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

    LaunchedEffect(Unit) {
        viewModel.loadDummySgf()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Goban Reader - Reading") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .navigationBarsPadding()
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (uiState.gameRecord != null) "SGF読み込み成功！" else "データを読み込み中...",
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().height(80.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 古いデバッグコードを削除し、GoBoardのみにする
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
