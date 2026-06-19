package com.github.tkirino.gobanreader.data

/**
 * 碁盤上の石の色を表す列挙型
 */
enum class StoneColor {
    BLACK, WHITE, NONE
}

/**
 * 囲碁の「一手の情報」を保持するデータクラス
 * 座標は(0,0)〜(18,18)のゼロインデックス、パス（着手放棄）は(-1,-1)などで表現します
 */
data class Move(
    val color: StoneColor,
    val x: Int,
    val y: Int,
    val moveNumber: Int // 何手目か
)

/**
 * SGFから解析された「対局全体の情報」を保持するデータクラス
 */
data class GameRecord(
    val komi: Float = 6.5f,
    val handicap: Int = 0,
    val blackPlayer: String = "",
    val whitePlayer: String = "",
    val moves: List<Move> = emptyList() // 初手から最終手までのリスト
)

/**
 * SGFテキストを解析するパーサークラス
 */
class SgfParser {

    /**
     * Sgfの文字列を受け取り、GameRecordオブジェクトに変換して返す関数
     */
    fun parse(sgfText: String): GameRecord {
        // TODO: ここに解析ロジックを実装していく
        return GameRecord()
    }
}

