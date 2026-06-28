package com.github.tkirino.gobanreader

import androidx.lifecycle.ViewModel
import com.github.tkirino.gobanreader.model.ReaderUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
// 【追加】BuildConfigとSgfParserのインポート
import com.github.tkirino.gobanreader.BuildConfig
import com.github.tkirino.gobanreader.export.SgfParser
import com.github.tkirino.gobanreader.model.StoneColor
import kotlin.collections.get
import com.github.tkirino.gobanreader.model.GameRecord
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * ViewModel containing the app data and methods to process the data
 */
class MainViewModel : ViewModel() {
    // UI状態を管理するStateFlow
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()
   ///////////
   // UIの状態を管理するStateFlow（既存のものを想定）
   // val uiState: StateFlow<UiState> = ...
   // ここから、下の　////// までの部分はテスト用のダミーコード
   var toastMessage by mutableStateOf<String?>(null)

    init {
        // アプリ（ViewModel）が起動したときに自動で実行される初期化ブロック
        initGame()
    }

    private fun initGame() {
        // 【追加】デバッグ時のみ自動でダミーのSGFをパースして状態にセットする
        if (BuildConfig.DEBUG) {
            loadDummySgf()
        } else {
            // 本番用の初期化（空のGameRecordを作るなど）
        }
    }

    /**
     * 【追加】テスト用のダミーSGFデータを読み込む関数
     */
    fun loadDummySgf() {
        val dummySgfText = """
        (;GM[1]FF[4]AP[Zenith:7.0]SZ[19]HA[0]KM[6.5]CA[UTF-8]
        AB[pd][qp][cc][dc][ec][fc][gb][hb][ge][gf][fg][fi][dh][cg][cj]
        [bs][br][cq][cp][co][cn][do][bm][ep][eq][fp][fo][fn][go][ho][io][hm][hl]
        AW[cd][dd][ed][fd][gd][gc][hc][ic][ff][ci][di][ej][gk][fm][gm][gn]
        [dl][dm][dn][en][eo][bl][bp][dp][dq][dr][ds][cr][er][fq][gq][hp][jq][op])
    """.trimIndent()

        val parser = SgfParser()
        val record = parser.parse(dummySgfText)

        // 1. まずすべて EMPTY の 19x19 可変リスト（matrix）を組み立てる
        val matrix = MutableList(19) { MutableList(19) { StoneColor.EMPTY } }

        // 2. 黒石の初期配置をマッピング (x, y の座標に BLACK を入れる)
        for (coord in record.initialBlackStones) {
            val x = coord.first
            val y = coord.second
            if (x in 0..18 && y in 0..18) {
                matrix[y][x] = StoneColor.BLACK
            }
        }

        // 3. 白石の初期配置をマッピング (x, y の座標に WHITE を入れる)
        for (coord in record.initialWhiteStones) {
            val x = coord.first
            val y = coord.second
            if (x in 0..18 && y in 0..18) {
                matrix[y][x] = StoneColor.WHITE
            }
        }

        /**
        * 現在の boardLayout の状態からSGF文字列を生成する
        */
        fun generateCurrentSgfString(): String {
            // 【修正】非公開の _uiState ではなく、公開されている uiState から現在の値を取得します
            val currentState = _uiState.value
            val size = currentState.gameRecord.boardSize // 19

            val abBuilder = StringBuilder()
            val awBuilder = StringBuilder()

            // 19x19の配列を走査して、黒石・白石の座標をSGF形式に変換
            for (row in 0 until size) {
                for (col in 0 until size) {
                    val stone = currentState.boardLayout[row][col]
                    if (stone != StoneColor.EMPTY) {
                        // 数値座標 (0〜18) を SGFの文字座標 ('a'〜's') に変換
                        val xChar = ('a'.code + col).toChar()
                        val yChar = ('a'.code + row).toChar()
                        val coordStr = "[$xChar$yChar]"

                        when (stone) {
                            StoneColor.BLACK -> abBuilder.append(coordStr)
                            StoneColor.WHITE -> awBuilder.append(coordStr)
                            else -> {}
                        }
                    }
                }
            }

            // 元のヘッダー情報を活かしつつ、現在の配置（回転反映後）を反映したSGFを組み立てる
            val sb = java.lang.StringBuilder()
            sb.append("(;GM[1]FF[4]SZ[$size]")
            sb.append("KM[${currentState.gameRecord.komi}]")
            sb.append("HA[${currentState.gameRecord.handicap}]")
            if (currentState.gameRecord.blackPlayer.isNotEmpty()) sb.append("PB[${currentState.gameRecord.blackPlayer}]")
            if (currentState.gameRecord.whitePlayer.isNotEmpty()) sb.append("PW[${currentState.gameRecord.whitePlayer}]")
            if (currentState.gameRecord.gameResult.isNotEmpty()) sb.append("RE[${currentState.gameRecord.gameResult}]")
            sb.append("CA[UTF-8]\n")

            // 黒石の配置を出力
            if (abBuilder.isNotEmpty()) {
                sb.append("AB").append(abBuilder).append("\n")
            }
            // 白石の配置を出力
            if (awBuilder.isNotEmpty()) {
                sb.append("AW").append(awBuilder).append("\n")
            }

            sb.append(")")
            return sb.toString()
        }

        // 4. 完成した19x19のデータを ReaderUiState の boardLayout に流し込む
        _uiState.update { currentState ->
            currentState.copy(
                gameRecord = record,
                boardLayout = matrix.map { it.toList() } // 不変のListに変換
            )
        }
    }

    // --- 右回転 ---
    fun rotateRight() {
        _uiState.update { currentState ->
            val currentLayout = currentState.boardLayout
            val size = currentState.gameRecord.boardSize // 動的にサイズを取得 (19)

            val rotated = List(size) { row ->
                List(size) { col ->
                    currentLayout[size - 1 - col][row]
                }
            }
            currentState.copy(boardLayout = rotated)
        }
    }

    // --- 左回転 ---
    fun rotateLeft() {
        _uiState.update { currentState ->
            val currentLayout = currentState.boardLayout
            val size = currentState.gameRecord.boardSize // 動的にサイズを取得 (19)

            val rotated = List(size) { row ->
                List(size) { col ->
                    currentLayout[col][size - 1 - row]
                }
            }
            currentState.copy(boardLayout = rotated)
        }
    }

    // 黒番のプレイヤー名を更新
    fun updateBlackPlayer(name: String) {
        _uiState.update { currentState ->
            currentState.copy(
                // gameRecordの中身だけを書き換えた新しいGameRecordを作る
                gameRecord = currentState.gameRecord.copy(blackPlayer = name)
            )
        }
    }

    // 白番のプレイヤー名を更新
    fun updateWhitePlayer(name: String) {
        _uiState.update { currentState ->
            currentState.copy(
                // gameRecordの中身だけを書き換えた新しいGameRecordを作る
                gameRecord = currentState.gameRecord.copy(whitePlayer = name)
            )
        }
    }
    fun updateNextPlayer(nextPlayer: String) {
        _uiState.update { currentState ->
            currentState.copy(
                gameRecord = currentState.gameRecord.copy(nextPlayer = nextPlayer)
            )
        }
    }

    /**
     * 現在の対局レコードをSGFファイルとして自動保存する
     * 画面側から context を受け取って処理します
     */
    /**
     * 現在画面に表示されている向き（回転後）の配置でSGFファイルを出力する
     */
    fun exportSgf(context: android.content.Context, gameRecord: com.github.tkirino.gobanreader.model.GameRecord) {
        viewModelScope.launch {
            // 1. 現在の画面の配置（boardLayout）を取得
            val currentLayout = _uiState.value.boardLayout
            val size = gameRecord.boardSize

            // 2. 画面の配置から、回転後の「初期配置の石」を再抽出する
            val rotatedInitialBlack = mutableListOf<Pair<Int, Int>>()
            val rotatedInitialWhite = mutableListOf<Pair<Int, Int>>()

            for (y in 0 until size) {
                for (x in 0 until size) {
                    // StoneColor（EMPTY / BLACK / WHITE）に合わせて分岐
                    when (currentLayout[y][x]) {
                        com.github.tkirino.gobanreader.model.StoneColor.BLACK -> {
                            // SGFの座標系に合わせて (x, y) のペアを追加
                            rotatedInitialBlack.add(Pair(x, y))
                        }
                        com.github.tkirino.gobanreader.model.StoneColor.WHITE -> {
                            rotatedInitialWhite.add(Pair(x, y))
                        }
                        com.github.tkirino.gobanreader.model.StoneColor.EMPTY -> {
                            /* 空点は何もしない */
                        }
                    }
                }
            }

            // 3. 回転後の石の配置を持った、出力専用の「新しいGameRecord」を作成する
            val rotatedGameRecord = gameRecord.copy(
                initialBlackStones = rotatedInitialBlack,
                initialWhiteStones = rotatedInitialWhite
                // 💡 今回のアプリは写真から現在の局面を切り出す仕様（初期配置のみ）のため、
                // moveHistory（着手履歴）は空のままで問題ありません。
            )

            // 4. 新しいGameRecordを使ってSGF文字列を生成・保存
            val sgfWriter = com.github.tkirino.gobanreader.export.SgfWriter(context)
            val sgfContent = sgfWriter.generateSgfString(rotatedGameRecord)

            val result = sgfWriter.saveSgfFileAutoNamed(sgfContent)

            result.onSuccess { file ->
                toastMessage = "保存完了: ${file.name}"
            }.onFailure { exception ->
                toastMessage = "保存に失敗しました"
                exception.printStackTrace()
            }
        }
    }
}
