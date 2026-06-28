package com.github.tkirino.gobanreader.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.tkirino.gobanreader.MainViewModel
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.navigationBarsPadding

@Composable
fun SettingScreen(
    viewModel: MainViewModel,
    onBlackPlayerChanged: (String) -> Unit,
    onWhitePlayerChanged: (String) -> Unit,
    onGetGobanClick: () -> Unit,
    onHistoryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 現在の次の手番を取得（デフォルトが空なら "B" をデフォルト視する）
    val nextPlayer = uiState.gameRecord.nextPlayer.ifEmpty { "B" }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 【追加】アプリロゴの表示
        Image(
            painter = painterResource(id = com.github.tkirino.gobanreader.R.drawable.logo),
            contentDescription = "GobanReader Logo",
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp), // ロゴの高さ（お好みに合わせて調整してください）
            contentScale = ContentScale.Inside // アスペクト比を保ったまま収める
        )

        // 【追加】ロゴとタイトルの間のスペース
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "対局情報の入力",
            style = MaterialTheme.typography.headlineMedium
        )

        // 必須項目：置き石
        OutlinedTextField(
            value = if (uiState.gameRecord.handicap == 0) "互先 (0)"
            else "${uiState.gameRecord.handicap}子",
            onValueChange = { /* 変更処理をViewModelに通知 */ },
            label = { Text("置き石 (必須)") },
            modifier = Modifier.fillMaxWidth()
        )

        // 必須項目：コミ
        OutlinedTextField(
            value = uiState.gameRecord.komi.toString(),
            onValueChange = { /* 変更処理をViewModelに通知 */ },
            label = { Text("コミ (必須)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        // 【追加】必須項目：次の手番の選択
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "次の手番 (必須)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 黒番ボタン
                if (nextPlayer == "B") {
                    Button(
                        onClick = { viewModel.updateNextPlayer("B") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("黒番 (先手)")
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.updateNextPlayer("B") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("黒番 (先手)")
                    }
                }

                // 白番ボタン
                if (nextPlayer == "W") {
                    Button(
                        onClick = { viewModel.updateNextPlayer("W") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("白番")
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.updateNextPlayer("W") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("白番")
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // 任意項目：対局者名など
        OutlinedTextField(
            value = uiState.gameRecord.blackPlayer,
            onValueChange = { viewModel.updateBlackPlayer(it) },
            label = { Text("黒番の対局者名 (省略可)") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = uiState.gameRecord.whitePlayer,
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
