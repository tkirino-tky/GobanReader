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
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
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

    private fun createArithmeticGrid(width: Double, height: Double, boardMat: Mat? = null): Array<Array<Point>> {
        val stepX = width / 19.0
        val stepY = height / 19.0

        val centerIndex = 9.0
        val baseCenterX = (centerIndex + 0.5) * stepX
        val baseCenterY = (centerIndex + 0.5) * stepY

        var offsetX = 0.0
        var offsetY = 0.0

        if (boardMat != null) {
            val detectedOffset = estimateGridOffset(boardMat, stepX, stepY)
            if (detectedOffset != null) {
                offsetX = detectedOffset.first
                offsetY = detectedOffset.second
                Log.d("GridAdjustment", "検出されたオフセット -> offsetX: $offsetX, offsetY: $offsetY")
            }
        }

        return Array(19) { r ->
            Array(19) { c ->
                val x = baseCenterX + (c - centerIndex) * stepX + offsetX
                val y = baseCenterY + (r - centerIndex) * stepY + offsetY
                Point(x, y)
            }
        }
    }

    private fun estimateGridOffset(boardMat: Mat, stepX: Double, stepY: Double): Pair<Double, Double>? {
        val searchCenters = listOf(
            Pair(9, 9), Pair(9, 8), Pair(8, 9), Pair(8, 8),
            Pair(9, 10), Pair(10, 9), Pair(10, 10), Pair(8, 10), Pair(10, 8),
            Pair(7, 9), Pair(9, 7), Pair(11, 9), Pair(9, 11)
        )

        val patchSize = 40

        for ((r, c) in searchCenters) {
            val roughX = (c + 0.5) * stepX
            val roughY = (r + 0.5) * stepY

            val x = roughX.toInt()
            val y = roughY.toInt()
            val half = patchSize / 2
            val x1 = (x - half).coerceIn(0, boardMat.cols() - patchSize)
            val y1 = (y - half).coerceIn(0, boardMat.rows() - patchSize)
            val rect = Rect(x1, y1, patchSize, patchSize)

            if (rect.width == patchSize && rect.height == patchSize) {
                val patch = Mat(boardMat, rect)
                val localOffset = findExactIntersectionInPatch(patch, patchSize)
                patch.release()

                if (localOffset != null) {
                    val exactX = x1 + localOffset.first
                    val exactY = y1 + localOffset.second
                    val dx = exactX - roughX
                    val dy = exactY - roughY

                    if (Math.abs(dx) < stepX * 0.3 && Math.abs(dy) < stepY * 0.3) {
                        return Pair(dx, dy)
                    }
                }
            }
        }
        return null
    }

    private fun findExactIntersectionInPatch(patch: Mat, patchSize: Int): Pair<Double, Double>? {
        val gray = Mat()
        if (patch.channels() > 1) {
            Imgproc.cvtColor(patch, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            patch.copyTo(gray)
        }

        val rowSum = DoubleArray(patchSize)
        val colSum = DoubleArray(patchSize)

        for (row in 0 until patchSize) {
            for (col in 0 until patchSize) {
                val intensity = gray.get(row, col)[0]
                rowSum[row] += intensity
                colSum[col] += intensity
            }
        }
        gray.release()

        var minX = -1.0
        var minY = -1.0
        var minRowVal = Double.MAX_VALUE
        var minColVal = Double.MAX_VALUE

        val margin = patchSize / 4
        for (i in margin until patchSize - margin) {
            if (rowSum[i] < minRowVal) {
                minRowVal = rowSum[i]
                minY = i.toDouble()
            }
            if (colSum[i] < minColVal) {
                minColVal = colSum[i]
                minX = i.toDouble()
            }
        }

        if (minX >= 0 && minY >= 0) {
            return Pair(minX, minY)
        }
        return null
    }

    /**
     * 碁盤座標検出用の二値化画像を Download/Cropped_Rect に 256x256 で保存する関数
     * （アノテーションCSVは出力しません。将来無効化する場合はこの関数の呼び出しをコメントアウトしてください）
     */
    private fun exportCroppedRectImage(srcMat: Mat) {
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val baseDir = File(downloadsDir, "Cropped_Rect")
            val sampleId = "sample_${System.currentTimeMillis()}"
            val sampleDir = File(baseDir, sampleId).apply {
                if (!exists()) mkdirs()
            }

            val destImageFile = File(sampleDir, "board_binary.png")

            // 必ず clone() を使用して元のMatを保護し、256x256にリサイズする
            val clonedMat = srcMat.clone()
            val resizedMat = Mat()
            Imgproc.resize(clonedMat, resizedMat, Size(256.0, 256.0))

            Imgcodecs.imwrite(destImageFile.absolutePath, resizedMat)

            clonedMat.release()
            resizedMat.release()

            Log.d("CroppedRectExport", "二値化画像の保存成功: ${destImageFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("CroppedRectExport", "二値化画像の保存中にエラーが発生しました", e)
        }
    }

    fun processWithCorners(corners: List<Point>) {
        val src = lastSourceMat ?: return

        viewModelScope.launch(Dispatchers.Default) {
            try {
                _uiState.update { it.copy(initialCorners = corners, isLoading = true) }

                val rectifiedMat = BoardRectifier.rectify(src, corners)
                val geometryGrid = createArithmeticGrid(rectifiedMat.cols().toDouble(), rectifiedMat.rows().toDouble())

                // 碁盤座標検出用の二値化画像を生成・書き出し（必要に応じてここで呼び出しをコメントアウト可能です）
                val gray = Mat()
                Imgproc.cvtColor(rectifiedMat, gray, Imgproc.COLOR_BGR2GRAY)
                val blurred = Mat()
                Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
                val edgeMat = Mat()
                Imgproc.Canny(blurred, edgeMat, 50.0, 150.0)
                Imgproc.dilate(edgeMat, edgeMat, Mat(), Point(-1.0, -1.0), 2)

                // 二値化画像を 256x256 で Download/Cropped_Rect に出力
                exportCroppedRectImage(edgeMat)

                blurred.release()
                edgeMat.release()
                gray.release()

                val stoneDetector = StoneDetector()
                val stoneResult = stoneDetector.detectStones(rectifiedMat, geometryGrid)
                val blackCount = stoneResult.sumOf { row -> row.count { it == StoneColor.BLACK } }
                val whiteCount = stoneResult.sumOf { row -> row.count { it == StoneColor.WHITE } }
                Log.d("StoneDetectorDebug", "検出結果 -> 黒石: $blackCount 個, 白石: $whiteCount 個")

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

                        Imgcodecs.imwrite(colorFile.absolutePath, colorPatch)
                        Imgcodecs.imwrite(edgeFile.absolutePath, edgePatch)

                        val labelNum = when (boardLayout[r][c]) {
                            StoneColor.EMPTY -> 0
                            StoneColor.BLACK -> 1
                            StoneColor.WHITE -> 2
                        }

                        csvContent.append("$filenameBase,$r,$c,$labelNum\n")

                        colorPatch.release()
                        edgePatch.release()
                    }
                }
            }

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
                            val rectifiedMat = BoardRectifier.rectify(srcMat, corners)
                            val geometryGrid = createArithmeticGrid(rectifiedMat.cols().toDouble(), rectifiedMat.rows().toDouble())

                            val gray = Mat()
                            Imgproc.cvtColor(rectifiedMat, gray, Imgproc.COLOR_BGR2GRAY)
                            val blurred = Mat()
                            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
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
