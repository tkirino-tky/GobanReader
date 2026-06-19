package com.github.tkirino.gobanreader

// Stone state
enum class StoneState { EMPTY, BLACK, WHITE }

data class Move(
    val color: StoneState,
    val x: Int,
    val y: Int,
    val moveNumber: Int
)

/**
 * Data class that represents the GobanReader UI state
 */
data class ReaderUiState(
    val boardSize: Int = 19,
    val komi: Float = 6.5f,
    val handicap: Int = 0,
    val blackPlayer: String = "",
    val whitePlayer: String = "",
    val gameResult: String = "",
    val moveHistory: List<Move> = emptyList(),
    val boardLayout: List<List<StoneState>> =
        List(boardSize) { List(boardSize) { StoneState.EMPTY } },
    val blackCamptured: Int = 0,
    val whiteCaptured: Int = 0
)