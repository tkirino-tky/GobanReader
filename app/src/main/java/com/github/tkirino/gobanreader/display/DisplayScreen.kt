package com.github.tkirino.gobanreader.display

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tkirino.gobanreader.MainViewModel
import com.github.tkirino.gobanreader.utility.PreferencesManager

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

    // SGF出力用メールアドレス設定ダイアログの表示状態
    var showEmailDialog by remember { mutableStateOf(false) }
    // ダイアログ内の入力フィールド用保持ステート
    var emailInput by remember { mutableStateOf("") }

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
                Button(onClick = {
                    // SGF出力ボタンが押されたら、保存済みのメールアドレスを読み込んでダイアログを表示
                    emailInput = PreferencesManager.getSavedEmail(context)
                    showEmailDialog = true
                }) { Text("SGF出力") }
                Button(onClick = onBackClick) { Text("戻る") }
            }
        }
    }

    // メールアドレス入力 & SGF出力ダイアログ
    if (showEmailDialog) {
        AlertDialog(
            onDismissRequest = { showEmailDialog = false },
            title = { Text("SGF出力とメール送信") },
            text = {
                Column {
                    Text("送信先メールアドレスを入力してください。\n(空欄の場合は端末への保存のみ行います)")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("メールアドレス") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEmailDialog = false
                        // 入力されたアドレス（空欄なら空文字）をViewModelに渡して実行
                        viewModel.exportSgf(context, uiState.gameRecord, emailInput)
                    }
                ) {
                    Text("実行")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmailDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}
