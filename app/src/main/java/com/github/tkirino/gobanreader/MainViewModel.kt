package com.github.tkirino.gobanreader

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.tkirino.gobanreader.export.SgfParser
import com.github.tkirino.gobanreader.export.SgfWriter
import com.github.tkirino.gobanreader.model.GameRecord
import com.github.tkirino.gobanreader.model.ReaderUiState
import com.github.tkirino.gobanreader.model.StoneColor
import com.github.tkirino.gobanreader.utility.GeometryUtils
import com.github.tkirino.gobanreader.vision.BoardCornerDetector
import com.github.tkirino.gobanreader.vision.BoardRectifier
import com.github.tkirino.gobanreader.vision.GridLineDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()
    var toastMessage by mutableStateOf<String?>(null)
    private var lastSourceMat: Mat? = null

    fun loadPhotoForAdjustment(file: File) {
        viewModelScope.launch(Dispatchers.Default) {
            val src = Imgcodecs.imread(file.absolutePath)
            lastSourceMat?.release()
            lastSourceMat = src

            val result = BoardCornerDetector().detect(getApplication(), src)

            val guideRect = GeometryUtils.calculateGuideRect(src.cols().toDouble(), src.rows().toDouble())
            val detectedCorners = if (result.found) result.corners
            else getFallbackCorners(Rect(guideRect.x.toInt(), guideRect.y.toInt(), guideRect.width.toInt(), guideRect.height.toInt()))

            val bitmap = android.graphics.Bitmap.createBitmap(src.cols(), src.rows(), android.graphics.Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(src, bitmap)

            _uiState.update {
                it.copy(
                    adjustmentBitmap = bitmap,
                    initialCorners = detectedCorners,
                    rawCorners = detectedCorners
                )
            }
        }
    }

    fun processWithCorners(corners: List<Point>) {
        val src = lastSourceMat ?: return

        _uiState.update { it.copy(initialCorners = corners) }

        val rectifiedMat = BoardRectifier.rectify(src, corners)
        val gridDetector = GridLineDetector()
        val gray = Mat()
        Imgproc.cvtColor(rectifiedMat, gray, Imgproc.COLOR_BGR2GRAY)

        val horizontal = gridDetector.detectGridLines(gray, GridLineDetector.Axis.HORIZONTAL)
        val vertical = gridDetector.detectGridLines(gray, GridLineDetector.Axis.VERTICAL)

        if (horizontal != null && vertical != null) {
            Log.d("MainViewModel", "罫線検出成功: H=${horizontal.spacing}, V=${vertical.spacing}")
        }

        gray.release()
        rectifiedMat.release()
    }

    private fun getFallbackCorners(guideRect: Rect): List<Point> {
        val paddingX = guideRect.width * 0.07
        val paddingY = guideRect.height * 0.07
        return listOf(
            Point(guideRect.x + paddingX, guideRect.y + paddingY),
            Point(guideRect.x + guideRect.width - paddingX, guideRect.y + paddingY),
            Point(guideRect.x + guideRect.width - paddingX, guideRect.y + guideRect.height - paddingY),
            Point(guideRect.x + paddingX, guideRect.y + guideRect.height - paddingY)
        )
    }

    fun processCapturedPhoto(file: File) {
        loadPhotoForAdjustment(file)
        // 非同期処理完了後に値を参照するため、必要に応じてコールバック等への変更が必要ですが、
        // 現状の構造ではこの関数は一度整理を保留すべき箇所です
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

    override fun onCleared() {
        super.onCleared()
        lastSourceMat?.release()
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


