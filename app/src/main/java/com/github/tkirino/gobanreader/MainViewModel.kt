package com.github.tkirino.gobanreader

import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tkirino.gobanreader.export.SgfParser
import com.github.tkirino.gobanreader.export.SgfWriter
import com.github.tkirino.gobanreader.model.GameRecord
import com.github.tkirino.gobanreader.model.ReaderUiState
import com.github.tkirino.gobanreader.model.StoneColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    var toastMessage by mutableStateOf<String?>(null)
    var debugWarpedBoard by mutableStateOf<android.graphics.Bitmap?>(null)

    init {
        if (BuildConfig.DEBUG) {
            loadDummySgf()
        }
    }

    // --- 抽象化された窓口 ---
    // MainViewModel.kt の processCapturedPhoto メソッドのみを以下のように修正します
    // パラメータとして拡大率を受け取れるように変更
    fun processCapturedPhoto(file: File) {
        val src = Imgcodecs.imread(file.absolutePath)
        if (src.empty()) return

        // 1. ガイドフレーム座標の取得（現在は仮のベース座標ですが、ここが起点です）
        // 後にこの値が前回の推論結果等から取得されることになります
        val baseRect = Rect(307, 770, 2457, 2558)

        // 2. 5%のオフセットを含めた矩形計算 (API活用)
        // Rectの算術演算を避け、プロパティから計算します
        val offsetW = (baseRect.width * 0.05).toInt()
        val offsetH = (baseRect.height * 0.05).toInt()

        // 境界チェックAPI（coerceAtLeast/Most）を用いて、画像外へのアクセスを確実に防止
        val x = (baseRect.x - offsetW).coerceAtLeast(0)
        val y = (baseRect.y - offsetH).coerceAtLeast(0)
        val width = (baseRect.width + 2 * offsetW).coerceAtMost(src.cols() - x)
        val height = (baseRect.height + 2 * offsetH).coerceAtMost(src.rows() - y)

        val finalRect = Rect(x, y, width, height)

        // 3. 切り出しとデバッグ保存
        val roi = Mat(src, finalRect)
        val debugFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "debug_roi.png")

        // 画像保存（API）
        Imgcodecs.imwrite(debugFile.absolutePath, roi)

        // メモリ解放（API）
        roi.release()
        src.release()
    }

    // --- ロジックの復元 ---
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
        val matrix = MutableList(19) { MutableList(19) { StoneColor.EMPTY } }
        for (coord in record.initialBlackStones) { matrix[coord.second][coord.first] = StoneColor.BLACK }
        for (coord in record.initialWhiteStones) { matrix[coord.second][coord.first] = StoneColor.WHITE }

        _uiState.update { it.copy(gameRecord = record, boardLayout = matrix.map { it.toList() }) }
    }

    fun rotateRight() {
        _uiState.update { currentState ->
            val size = currentState.gameRecord.boardSize
            val rotated = List(size) { row -> List(size) { col -> currentState.boardLayout[size - 1 - col][row] } }
            currentState.copy(boardLayout = rotated)
        }
    }

    fun rotateLeft() {
        _uiState.update { currentState ->
            val size = currentState.gameRecord.boardSize
            val rotated = List(size) { row -> List(size) { col -> currentState.boardLayout[col][size - 1 - row] } }
            currentState.copy(boardLayout = rotated)
        }
    }

    fun updateBlackPlayer(name: String) {
        _uiState.update { it.copy(gameRecord = it.gameRecord.copy(blackPlayer = name)) }
    }

    fun updateWhitePlayer(name: String) {
        _uiState.update { it.copy(gameRecord = it.gameRecord.copy(whitePlayer = name)) }
    }

    fun updateNextPlayer(nextPlayer: String) {
        _uiState.update { it.copy(gameRecord = it.gameRecord.copy(nextPlayer = nextPlayer)) }
    }

    fun exportSgf(context: android.content.Context, gameRecord: GameRecord) {
        viewModelScope.launch {
            val currentLayout = _uiState.value.boardLayout
            val size = gameRecord.boardSize
            val rotatedInitialBlack = mutableListOf<Pair<Int, Int>>()
            val rotatedInitialWhite = mutableListOf<Pair<Int, Int>>()
            for (y in 0 until size) {
                for (x in 0 until size) {
                    when (currentLayout[y][x]) {
                        StoneColor.BLACK -> rotatedInitialBlack.add(Pair(x, y))
                        StoneColor.WHITE -> rotatedInitialWhite.add(Pair(x, y))
                        else -> {}
                    }
                }
            }
            val rotatedGameRecord = gameRecord.copy(initialBlackStones = rotatedInitialBlack, initialWhiteStones = rotatedInitialWhite)
            val sgfWriter = SgfWriter(context)
            val result = sgfWriter.saveSgfFileAutoNamed(sgfWriter.generateSgfString(rotatedGameRecord))
            result.onSuccess { toastMessage = "保存完了: ${it.name}" }.onFailure { toastMessage = "保存に失敗しました" }
        }
    }
}
