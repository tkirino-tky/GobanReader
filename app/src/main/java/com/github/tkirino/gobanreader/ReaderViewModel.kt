package com.github.tkirino.gobanreader

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ViewModel containing the app data and methods to process the data
 */
class ReaderViewModel : ViewModel() {
    // UI状態を管理するStateFlow
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    // 黒番のプレイヤー名を更新する関数
    fun updateBlackPlayer(name: String) {
        _uiState.update { currentState ->
            currentState.copy(blackPlayer = name)
        }
    }

    // 白番のプレイヤー名を更新する関数
    fun updateWhitePlayer(name: String) {
        _uiState.update { currentState ->
            currentState.copy(whitePlayer = name)
        }
    }

    // 必要に応じて、他の設定（コミや置き石）の更新関数もここに定義します
}


