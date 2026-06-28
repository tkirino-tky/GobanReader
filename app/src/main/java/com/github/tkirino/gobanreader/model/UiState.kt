package com.github.tkirino.gobanreader.model


// ... other imports ...

// ...
/**
 * Data class that represents the GobanReader UI state
 */
data class ReaderUiState(
    // 対局の基本情報・棋譜データはここにまとめる
    val gameRecord: GameRecord = GameRecord(),

    // 以下は、現在の画面（表示・認識）がリアルタイムに管理する状態
    val boardLayout: List<List<StoneColor>> =
        List(gameRecord.boardSize) { List(gameRecord.boardSize) { StoneColor.EMPTY } },
    val blackCaptured: Int = 0,
    val whiteCaptured: Int = 0,
    val isLoading: Boolean = false // 例：画像解析中などのUI状態もここに入れられる
)
