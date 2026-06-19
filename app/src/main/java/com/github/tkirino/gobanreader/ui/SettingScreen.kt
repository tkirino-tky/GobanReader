package com.github.tkirino.gobanreader.ui

// 追加：データの型を認識させるためのインポート
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tkirino.gobanreader.ReaderViewModel

@Composable
fun SettingScreen(
    viewModel: ReaderViewModel,
    onBlackPlayerChanged: (String) -> Unit,
    onWhitePlayerChanged: (String) -> Unit,
    onGetGobanClick: () -> Unit,
    onHistoryClick: () -> Unit,
    // Navigationでカメラ画面へ遷移するコールバック
    modifier: Modifier = Modifier
) {
    // ViewModelのStateFlowをComposeの状態として収集
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()), // 画面が小さくてもスクロール可能に
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "対局情報の入力",
            style = MaterialTheme.typography.headlineMedium
        )

        // 必須項目：置き石
        OutlinedTextField(
            value = if (uiState.handicap == 0) "互先 (0)" else "${uiState.handicap}子",
            onValueChange = { /* 変更処理をViewModelに通知 */ },
            label = { Text("置き石 (必須)") },
            modifier = Modifier.fillMaxWidth()
        )

        // 必須項目：コミ
        OutlinedTextField(
            value = uiState.komi.toString(),
            onValueChange = { /* 変更処理をViewModelに通知 */ },
            label = { Text("コミ (必須)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // 任意項目：対局者名など
        OutlinedTextField(
            value = uiState.blackPlayer,
            onValueChange = { viewModel.updateBlackPlayer(it) },
            label = { Text("黒番の対局者名 (省略可)") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = uiState.whitePlayer,
            onValueChange = { viewModel.updateWhitePlayer(it) },
            label = { Text("白番の対局者名 (省略可)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.weight(1f))

        // カメラ画面への遷移ボタン
        Button(
            onClick = onGetGobanClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("碁盤を撮影する", style = MaterialTheme.typography.titleMedium)
        }
    }
}
