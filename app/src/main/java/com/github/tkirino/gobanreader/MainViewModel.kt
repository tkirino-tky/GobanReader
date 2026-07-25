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
import com.github.tkirino.gobanreader.stones.StoneDetector
import com.github.tkirino.gobanreader.utility.GeometryUtils
import com.github.tkirino.gobanreader.utility.PreferencesManager
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
import org.opencv.imgproc.Imgproc
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()
    var toastMessage by mutableStateOf<String?>(null)
    private var lastSourceMat: Mat? = null

    fun updateHandicap(handicap: Int) {
        _uiState.value = _uiState.value.copy(
            gameRecord = _uiState.value.gameRecord.copy(handicap = handicap)
        )
    }

    fun updateKomi(komi: Float) {
        _uiState.value = _uiState.value.copy(
            gameRecord = _uiState.value.gameRecord.copy(komi = komi)
        )
    }

    fun loadPhotoForAdjustment(file: File) {
        viewModelScope.launch(Dispatchers.Default) {
            // 1. 画像ファイルをBitmapとして読み込み、Exifの回転情報を反映して正立させる
            val originalBitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            val rotatedBitmap = rotateBitmapIfNeeded(file.absolutePath, originalBitmap)

            // 2. 正立させたBitmapをOpenCVの Mat に変換する
            val src = Mat()
            Utils.bitmapToMat(rotatedBitmap, src)

            // RGBからBGRへ変換
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

            lastSourceMat?.release()
            lastSourceMat = src

            val result = BoardCornerDetector().detect(getApplication(), src)

            // 実際の画像サイズを基準にガイド矩形を算出
            val guideRect = GeometryUtils.calculateGuideRect(src.cols().toDouble(), src.rows().toDouble())

            val detectedCorners = if (result.found && result.corners.size == 4) {
                result.corners
            } else {
                getFallbackCorners(guideRect)
            }

            _uiState.update {
                it.copy(
                    adjustmentBitmap = rotatedBitmap,
                    initialCorners = detectedCorners,
                    rawCorners = detectedCorners
                )
            }
        }
    }

    // Exifの回転情報を読み取ってBitmapを正しく回転させるヘルパー関数
    private fun rotateBitmapIfNeeded(imagePath: String, bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        try {
            val exif = android.media.ExifInterface(imagePath)
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = android.graphics.Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }
            return android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            ).also {
                if (it != bitmap) {
                    bitmap.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Exifの読み取りに失敗しました", e)
            return bitmap
        }
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

    fun processWithCorners(corners: List<Point>) {
        val src = lastSourceMat ?: return

        viewModelScope.launch(Dispatchers.Default) {
            try {
                _uiState.update { it.copy(initialCorners = corners, isLoading = true) }

                val rectifiedMat = BoardRectifier.rectify(src, corners)
                val gridDetector = GridLineDetector()
                val gray = Mat()
                Imgproc.cvtColor(rectifiedMat, gray, Imgproc.COLOR_BGR2GRAY)

                val horizontal = gridDetector.detectGridLines(gray, GridLineDetector.Axis.HORIZONTAL)
                val vertical = gridDetector.detectGridLines(gray, GridLineDetector.Axis.VERTICAL)

                if (horizontal != null && vertical != null) {
                    Log.d("MainViewModel", "罫線検出成功: H=${horizontal.spacing}, V=${vertical.spacing}")

                    // 19×19 の交点グリッドを生成（余計な反転を削除し、正しい順序でマッピング）
                    val geometryGrid = Array(19) { r ->
                        Array(19) { c ->
                            val x = vertical.positions.getOrNull(c) ?: 0.0
                            val y = horizontal.positions.getOrNull(r) ?: 0.0
                            Point(x, y)
                        }
                    }

                    // StoneDetector による石の判定実行
                    val stoneDetector = StoneDetector()
                    val stoneResult = stoneDetector.detectStones(rectifiedMat, geometryGrid)
                    val blackCount = stoneResult.sumOf { row -> row.count { it == StoneColor.BLACK } }
                    val whiteCount = stoneResult.sumOf { row -> row.count { it == StoneColor.WHITE } }
                    Log.d("StoneDetectorDebug", "検出結果 -> 黒石: $blackCount 個, 白石: $whiteCount 個")

                    // 解析結果を UiState の boardLayout に反映
                    _uiState.update { currentState ->
                        currentState.copy(
                            isLoading = false,
                            boardLayout = stoneResult
                        )
                    }
                    toastMessage = "碁盤の解析が完了しました"
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                    toastMessage = "罫線の検出に失敗しました"
                }

                gray.release()
                rectifiedMat.release()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                toastMessage = "解析エラー: ${e.localizedMessage}"
            }
        }
    }

    fun processCapturedPhoto(file: File) {
        loadPhotoForAdjustment(file)
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
        Log.d("MainViewModelDebug", "rotateRight が呼ばれました")
        _uiState.update { currentState ->
            val size = currentState.gameRecord.boardSize
            try {
                val rotated = List(size) { row -> List(size) { col -> currentState.boardLayout[size - 1 - col][row] } }
                currentState.copy(boardLayout = rotated)
            } catch (e: Exception) {
                Log.e("MainViewModelDebug", "rotateRight 内部でエラー発生", e)
                currentState
            }
        }
    }

    fun rotateLeft() {
        Log.d("MainViewModelDebug", "rotateLeft が呼ばれました")
        _uiState.update { currentState ->
            val size = currentState.gameRecord.boardSize
            try {
                val rotated = List(size) { row -> List(size) { col -> currentState.boardLayout[col][size - 1 - row] } }
                currentState.copy(boardLayout = rotated)
            } catch (e: Exception) {
                Log.e("MainViewModelDebug", "rotateLeft 内部でエラー発生", e)
                currentState
            }
        }
    }

    // 既存の exportSgf を置き換え
    fun exportSgf(context: android.content.Context, gameRecord: GameRecord, recipientEmail: String) {
        viewModelScope.launch {
            // 1. メールアドレスを保存（デフォルトとして記憶）
            PreferencesManager.saveEmail(context, recipientEmail)

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
            val sgfString = sgfWriter.generateSgfString(rotatedGameRecord)
            val result = sgfWriter.saveSgfFileAutoNamed(sgfString)

            result.onSuccess { savedFile ->
                var message = "保存完了: ${savedFile.name}"

                // 2. メールアドレスが入力されている場合はメール送信（インテント起動）を行う
                if (recipientEmail.isNotBlank()) {
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            savedFile
                        )
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/x-go-sgf" // または "text/plain"
                            putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(recipientEmail))
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "GobanReader SGF出力")
                            putExtra(android.content.Intent.EXTRA_TEXT, "碁盤解析アプリからSGFファイルをお送りします。")
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        // メーラー起動はメインスレッドで実行する必要があるため Context を使って投げる
                        val chooser = android.content.Intent.createChooser(intent, "SGFファイルをメールで送信").apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chooser)
                        message += " & メール送信準備完了"
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "メール起動失敗", e)
                        message += " (メール起動に失敗しました)"
                    }
                }

                toastMessage = message
            }.onFailure {
                toastMessage = "保存に失敗しました"
            }
        }
    }
}
