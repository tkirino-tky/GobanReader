package com.github.tkirino.gobanreader

import android.util.Log
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
import com.github.tkirino.gobanreader.utility.GeometryUtils
import com.github.tkirino.gobanreader.vision.GuidedBoardDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

// MainViewModel.kt の修正
import android.app.Application
import androidx.lifecycle.AndroidViewModel // 追加
import org.opencv.imgproc.Imgproc

// 修正前: class MainViewModel : ViewModel() {
// 修正後:
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    var toastMessage by mutableStateOf<String?>(null)
    private val _debugWarpedBoard = mutableStateOf<android.graphics.Bitmap?>(null)
    var debugWarpedBoard: android.graphics.Bitmap?
        get() = _debugWarpedBoard.value
        set(value) { _debugWarpedBoard.value = value }

    init {
        if (BuildConfig.DEBUG) {
            loadDummySgf()
        }
    }

    fun processCapturedPhoto(file: File) {
        val src = Imgcodecs.imread(file.absolutePath)
        if (src.empty()) {
            Log.e("MainViewModel", "画像読み込み失敗: ${file.absolutePath}")
            return
        }

        val guideRect = GeometryUtils.calculateGuideRect(
            width = src.cols().toDouble(),
            height = src.rows().toDouble()
        )

        val cvRect = Rect(guideRect.x.toInt(), guideRect.y.toInt(), guideRect.width.toInt(), guideRect.height.toInt())
        val cropped = Mat(src, cvRect)
        val grayMat = Mat()
        Imgproc.cvtColor(cropped, grayMat, Imgproc.COLOR_BGR2GRAY)

        val detector = GuidedBoardDetector(getApplication(), cvRect)

        try {
            // 四隅の検出を実行
            Log.d("MainViewModel", "四隅検出を開始します")
            val corners = detector.detectCorners(grayMat)

            if (corners != null) {
                Log.d("MainViewModel", "四隅検出成功、歪み補正を実行します。")
                val warped = detector.warpBoard(cropped, corners)

                val bitmap = android.graphics.Bitmap.createBitmap(warped.cols(), warped.rows(), android.graphics.Bitmap.Config.ARGB_8888)
                org.opencv.android.Utils.matToBitmap(warped, bitmap)
                debugWarpedBoard = bitmap
                Log.d("MainViewModel", "★debugWarpedBoardのセット後、中身はnullですか？: ${debugWarpedBoard == null}")

                warped.release()
            } else {
                // ここでエラー理由を詳細に出力
                Log.e("MainViewModel", "四隅の検出に失敗しました (detectCorners が null を返しました)")
            }
        } catch (e: Exception) {
            // ここで例外をキャッチする
            Log.e("MainViewModel", "四隅検出中に例外が発生しました: ${e.message}", e)
        }

        _uiState.update { currentState -> currentState.copy(isLoading = false) }

        src.release()
        cropped.release()
    }

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
