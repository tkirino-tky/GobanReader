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
            val originalBitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            val rotatedBitmap = rotateBitmapIfNeeded(file.absolutePath, originalBitmap)

            val src = Mat()
            Utils.bitmapToMat(rotatedBitmap, src)
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

            lastSourceMat?.release()
            lastSourceMat = src

            val result = BoardCornerDetector().detect(getApplication(), src)
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

    /**
     * ワープ後の画像サイズ内（例: 800x800）で、19x19の交点を完全に等間隔な算術計算で生成する
     */
    private fun createArithmeticGrid(width: Double, height: Double): Array<Array<Point>> {
        // 碁盤は19路（18のインターバル）ですが、外側に半目分のマージン（計1マス分）を取って矩形化しているため、
        // 画像全体を「19等分」して各交点を配置するのが幾何学的に正しくなります。
        val stepX = width / 19.0
        val stepY = height / 19.0

        return Array(19) { r ->
            Array(19) { c ->
                // 端から半目分（stepの半分）オフセットした位置を中心とする
                val x = (c + 0.5) * stepX
                val y = (r + 0.5) * stepY
                Point(x, y)
            }
        }
    }

    fun processWithCorners(corners: List<Point>) {
        val src = lastSourceMat ?: return

        viewModelScope.launch(Dispatchers.Default) {
            try {
                _uiState.update { it.copy(initialCorners = corners, isLoading = true) }

                // 引数を元の正しい形 (src, corners) に修正
                val rectifiedMat = BoardRectifier.rectify(src, corners)

                // ワーピング後の画像の実際のサイズを基準に等間隔グリッドを生成
                val geometryGrid = createArithmeticGrid(rectifiedMat.cols().toDouble(), rectifiedMat.rows().toDouble())

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
        _uiState.update { currentState ->
            val size = currentState.gameRecord.boardSize
            try {
                val rotated = List(size) { row -> List(size) { col -> currentState.boardLayout[size - 1 - col][row] } }
                currentState.copy(boardLayout = rotated)
            } catch (e: Exception) {
                currentState
            }
        }
    }

    fun rotateLeft() {
        _uiState.update { currentState ->
            val size = currentState.gameRecord.boardSize
            try {
                val rotated = List(size) { row -> List(size) { col -> currentState.boardLayout[col][size - 1 - row] } }
                currentState.copy(boardLayout = rotated)
            } catch (e: Exception) {
                currentState
            }
        }
    }

    private fun exportDatasetPair(
        rectifiedMat: Mat,
        edgeMat: Mat,
        geometryGrid: Array<Array<Point>>,
        boardLayout: List<List<StoneColor>>
    ) {
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val baseDir = File(downloadsDir, "goban_dataset")
            val gameId = "game_${System.currentTimeMillis()}"
            val gameFolder = File(baseDir, gameId)
            if (!gameFolder.exists()) {
                gameFolder.mkdirs()
            }

            val patchSize = 40

            // CSVの中身をすべて保持するビルダー
            val csvContent = StringBuilder()
            csvContent.append("filename_base,row,col,label\n")

            for (r in 0 until 19) {
                for (c in 0 until 19) {
                    val center = geometryGrid[r][c]
                    val x = center.x.toInt()
                    val y = center.y.toInt()

                    val half = patchSize / 2
                    val x1 = (x - half).coerceIn(0, rectifiedMat.cols() - patchSize)
                    val y1 = (y - half).coerceIn(0, rectifiedMat.rows() - patchSize)
                    val rect = Rect(x1, y1, patchSize, patchSize)

                    if (rect.width > 0 && rect.height > 0) {
                        val colorPatch = Mat(rectifiedMat, rect)
                        val edgePatch = Mat(edgeMat, rect)

                        val filenameBase = "r${r}_c${c}"
                        val colorFile = File(gameFolder, "${filenameBase}_color.png")
                        val edgeFile = File(gameFolder, "${filenameBase}_edge.png")

                        org.opencv.imgcodecs.Imgcodecs.imwrite(colorFile.absolutePath, colorPatch)
                        org.opencv.imgcodecs.Imgcodecs.imwrite(edgeFile.absolutePath, edgePatch)

                        val labelNum = when (boardLayout[r][c]) {
                            StoneColor.EMPTY -> 0
                            StoneColor.BLACK -> 1
                            StoneColor.WHITE -> 2
                        }

                        // 文字列としてバッファ（メモリ上のStringBuilder）に溜める
                        csvContent.append("$filenameBase,$r,$c,$labelNum\n")

                        colorPatch.release()
                        edgePatch.release()
                    }
                }
            }

            // ループがすべて終わったあとに、一気にファイルへ書き込む（確実にディスクに保存される）
            val csvFile = File(gameFolder, "labels.csv")
            csvFile.writeText(csvContent.toString())

            Log.d("DatasetExport", "labels.csv の書き込みが完了しました。サイズ: ${csvFile.length()} バイト")

        } catch (e: Exception) {
            Log.e("DatasetExport", "データセット出力中にエラーが発生しました", e)
        }
    }

    fun exportSgf(context: android.content.Context, gameRecord: GameRecord, recipientEmail: String) {
        viewModelScope.launch {
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

                try {
                    val srcMat = lastSourceMat
                    if (srcMat != null) {
                        val corners = _uiState.value.initialCorners
                        if (corners.size == 4) {
                            // 引数を (srcMat, corners) の2つに修正
                            val rectifiedMat = BoardRectifier.rectify(srcMat, corners)
                            val geometryGrid = createArithmeticGrid(rectifiedMat.cols().toDouble(), rectifiedMat.rows().toDouble())

                            val gray = Mat()
                            Imgproc.cvtColor(rectifiedMat, gray, Imgproc.COLOR_BGR2GRAY)
                            val blurred = Mat()
                            Imgproc.GaussianBlur(gray, blurred, org.opencv.core.Size(5.0, 5.0), 0.0)
                            val edgeMat = Mat()
                            Imgproc.Canny(blurred, edgeMat, 50.0, 150.0)
                            Imgproc.dilate(edgeMat, edgeMat, Mat(), Point(-1.0, -1.0), 2)

                            exportDatasetPair(rectifiedMat, edgeMat, geometryGrid, currentLayout)

                            blurred.release()
                            edgeMat.release()
                            gray.release()
                            rectifiedMat.release()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GobanExport", "データセットのエクスポート中にエラーが発生しました", e)
                }
                
                toastMessage = message
            }.onFailure {
                Log.e("MainViewModel", "SGFファイルの保存に失敗しました", it)
                toastMessage = "保存に失敗しました: ${it.localizedMessage}"
            }
        }
    }
}
