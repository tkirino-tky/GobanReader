package com.github.tkirino.gobanreader

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.tkirino.gobanreader.config.DebugConfig
import com.github.tkirino.gobanreader.export.SgfParser
import com.github.tkirino.gobanreader.export.SgfWriter
import com.github.tkirino.gobanreader.model.GameRecord
import com.github.tkirino.gobanreader.model.ReaderUiState
import com.github.tkirino.gobanreader.model.StoneColor
import com.github.tkirino.gobanreader.stones.CnnStoneDetector
import com.github.tkirino.gobanreader.utility.GeometryUtils
import com.github.tkirino.gobanreader.utility.PreferencesManager
import com.github.tkirino.gobanreader.vision.BoardRectifier
import com.github.tkirino.gobanreader.vision.CnnCornerDetector
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
import org.pytorch.LiteModuleLoader
import java.io.File
import java.io.FileOutputStream

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()
    var toastMessage by mutableStateOf<String?>(null)
    private var lastSourceMat: Mat? = null

    private var cnnCornerDetector: CnnCornerDetector? = null

    var cornerQualityMessage by mutableStateOf("")
        private set
    var isCornerQualityGood by mutableStateOf(true)
        private set

    init {
        try {
            val modelPath = assetFilePath(application, "board_corner_model.ptl")
            val torchModule = LiteModuleLoader.load(modelPath)
            cnnCornerDetector = CnnCornerDetector(torchModule)
        } catch (e: Exception) {
            Log.e("MainViewModel", "モデルのロードに失敗しました", e)
        }
    }

    private fun assetFilePath(context: android.content.Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (!file.exists()) {
            context.assets.open(assetName).use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return file.absolutePath
    }

    fun updateHandicap(handicap: Int) {
        _uiState.value = _uiState.value.copy(gameRecord = _uiState.value.gameRecord.copy(handicap = handicap))
    }

    fun updateKomi(komi: Float) {
        _uiState.value = _uiState.value.copy(gameRecord = _uiState.value.gameRecord.copy(komi = komi))
    }

    var remoteShutterTrigger by mutableStateOf(0)
        private set

    fun triggerRemoteShutter() {
        remoteShutterTrigger++
    }

    fun loadPhotoForAdjustment(file: File) {
        viewModelScope.launch(Dispatchers.Default) {
            val detector = cnnCornerDetector
            if (detector == null) {
                toastMessage = "検出器の初期化に失敗しています"
                return@launch
            }

            val originalBitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            val rotatedBitmap = rotateBitmapIfNeeded(file.absolutePath, originalBitmap)

            val fullSrc = Mat()
            Utils.bitmapToMat(rotatedBitmap, fullSrc)
            Imgproc.cvtColor(fullSrc, fullSrc, Imgproc.COLOR_RGBA2BGR)

            val guideRect = GeometryUtils.calculateGuideRect(fullSrc.cols().toDouble(), fullSrc.rows().toDouble())
            val cnnResult = detector.detectCorners(fullSrc, guideRect)

            val detectedCorners = if (cnnResult.found && cnnResult.corners.size == 4) {
                cnnResult.corners
            } else {
                listOf(
                    org.opencv.core.Point(guideRect.x.toDouble(), guideRect.y.toDouble()),
                    org.opencv.core.Point((guideRect.x + guideRect.width).toDouble(), guideRect.y.toDouble()),
                    org.opencv.core.Point((guideRect.x + guideRect.width).toDouble(), (guideRect.y + guideRect.height).toDouble()),
                    org.opencv.core.Point(guideRect.x.toDouble(), (guideRect.y + guideRect.height).toDouble())
                )
            }

            if (!cnnResult.found) {
                cornerQualityMessage = "座標の取得に失敗しました"
                isCornerQualityGood = false
            } else if (!isCornerQualityHigh(detectedCorners, fullSrc.cols().toDouble(), fullSrc.rows().toDouble())) {
                cornerQualityMessage = "罫線のかどの位置を手動で変更してください"
                isCornerQualityGood = false
            } else {
                cornerQualityMessage = "座標の変更の必要はありません"
                isCornerQualityGood = true
            }

            lastSourceMat?.release()
            lastSourceMat = fullSrc.clone()

            _uiState.update {
                it.copy(
                    adjustmentBitmap = rotatedBitmap,
                    initialCorners = detectedCorners,
                    rawCorners = detectedCorners
                )
            }

            fullSrc.release()
        }
    }

    private fun isCornerQualityHigh(corners: List<org.opencv.core.Point>, imgWidth: Double, imgHeight: Double): Boolean {
        if (corners.size != 4) return false
        val p0 = corners[0]; val p1 = corners[1]; val p2 = corners[2]; val p3 = corners[3]
        val topWidth = Math.hypot(p1.x - p0.x, p1.y - p0.y)
        val bottomWidth = Math.hypot(p2.x - p3.x, p2.y - p3.y)
        val leftHeight = Math.hypot(p3.x - p0.x, p3.y - p0.y)
        val rightHeight = Math.hypot(p2.x - p1.x, p2.y - p1.y)

        if (topWidth < 100.0 || bottomWidth < 100.0 || leftHeight < 100.0 || rightHeight < 100.0) return false
        if (topWidth > imgWidth || bottomWidth > imgWidth || leftHeight > imgHeight || rightHeight > imgHeight) return false
        val widthRatio = kotlin.math.max(topWidth, bottomWidth) / kotlin.math.min(topWidth, bottomWidth)
        val heightRatio = kotlin.math.max(leftHeight, rightHeight) / kotlin.math.min(leftHeight, rightHeight)
        return widthRatio <= 1.6 && heightRatio <= 1.6
    }

    private fun rotateBitmapIfNeeded(imagePath: String, bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        try {
            val exif = android.media.ExifInterface(imagePath)
            val orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            val matrix = android.graphics.Matrix()
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }
            return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it != bitmap) bitmap.recycle() }
        } catch (e: Exception) { return bitmap }
    }

    private fun createArithmeticGrid(width: Double, height: Double, boardMat: Mat? = null): Array<Array<org.opencv.core.Point>> {
        val stepX = width / 19.0
        val stepY = height / 19.0
        val centerIndex = 9.0
        val baseCenterX = (centerIndex + 0.5) * stepX
        val baseCenterY = (centerIndex + 0.5) * stepY
        return Array(19) { r -> Array(19) { c -> org.opencv.core.Point(baseCenterX + (c - centerIndex) * stepX, baseCenterY + (r - centerIndex) * stepY) } }
    }

    fun processWithCorners(corners: List<org.opencv.core.Point>) {
        val src = lastSourceMat?.clone() ?: return
        val detector = cnnCornerDetector ?: return

        viewModelScope.launch(Dispatchers.Default) {
            try {
                _uiState.update { it.copy(initialCorners = corners, isLoading = true) }
                val rectifiedMat = BoardRectifier.rectify(src, corners)
                src.release()

                val geometryGrid = createArithmeticGrid(rectifiedMat.cols().toDouble(), rectifiedMat.rows().toDouble())
                val edgeMat = detector.generateEdgeImage(rectifiedMat)

                val cnnDetector = CnnStoneDetector(getApplication())
                val stoneResult = cnnDetector.detectStones(rectifiedMat, edgeMat, geometryGrid)
                edgeMat.release()

                _uiState.update { it.copy(isLoading = false, boardLayout = stoneResult) }
                toastMessage = "碁盤の解析が完了しました"
                rectifiedMat.release()
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                toastMessage = "解析エラー: ${e.localizedMessage}"
            }
        }
    }

    fun processCapturedPhoto(file: File) { loadPhotoForAdjustment(file) }
    fun updateBlackPlayer(name: String) { _uiState.update { it.copy(gameRecord = it.gameRecord.copy(blackPlayer = name)) } }
    fun updateWhitePlayer(name: String) { _uiState.update { it.copy(gameRecord = it.gameRecord.copy(whitePlayer = name)) } }
    fun updateNextPlayer(nextPlayer: String) { _uiState.update { it.copy(gameRecord = it.gameRecord.copy(nextPlayer = nextPlayer)) } }

    fun updateStone(row: Int, col: Int, color: StoneColor) {
        _uiState.update { state ->
            val newLayout = state.boardLayout.mapIndexed { r, list ->
                if (r == row) {
                    list.mapIndexed { c, current -> if (c == col) color else current }
                } else {
                    list
                }
            }
            state.copy(boardLayout = newLayout)
        }
    }

    fun rotateLeft() {
        _uiState.update { state ->
            val current = state.boardLayout
            val size = current.size
            val newLayout = List(size) { r -> List(size) { c -> current[c][size - 1 - r] } }
            state.copy(boardLayout = newLayout)
        }
    }

    fun rotateRight() {
        _uiState.update { state ->
            val current = state.boardLayout
            val size = current.size
            val newLayout = List(size) { r -> List(size) { c -> current[size - 1 - c][r] } }
            state.copy(boardLayout = newLayout)
        }
    }

    override fun onCleared() {
        super.onCleared()
        lastSourceMat?.release()
    }

    fun loadDummySgf() {
        val dummySgfText = "(;GM[1]FF[4]AP[Zenith:7.0]SZ[19]HA[0]KM[6.5]CA[UTF-8]AB[pd][qp][cc][dc][ec][fc][gb][hb][ge][gf][fg][fi][dh][cg][cj][bs][br][cq][cp][co][cn][do][bm][ep][eq][fp][fo][fn][go][ho][io][hm][hl]AW[cd][dd][ed][fd][gd][gc][hc][ic][ff][ci][di][ej][gk][fm][gm][gn][dl][dm][dn][en][eo][bl][bp][dp][dq][dr][ds][cr][er][fq][gq][hp][jq][op])".trimIndent()
        val parser = SgfParser()
        val record = parser.parse(dummySgfText)
        val matrix = MutableList(19) { MutableList(19) { StoneColor.EMPTY } }
        for (coord in record.initialBlackStones) { matrix[coord.second][coord.first] = StoneColor.BLACK }
        for (coord in record.initialWhiteStones) { matrix[coord.second][coord.first] = StoneColor.WHITE }
        _uiState.update { it.copy(gameRecord = record, boardLayout = matrix.map { it.toList() }) }
    }

    private fun exportDatasetPair(rectifiedMat: Mat, edgeMat: Mat, geometryGrid: Array<Array<org.opencv.core.Point>>, boardLayout: List<List<StoneColor>>) {
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val baseDir = File(downloadsDir, "goban_dataset")
            val gameId = "game_${System.currentTimeMillis()}"
            val gameFolder = File(baseDir, gameId)
            if (!gameFolder.exists()) gameFolder.mkdirs()

            val patchSize = 40
            val csvContent = StringBuilder("filename_base,row,col,label\n")

            for (r in 0 until 19) {
                for (c in 0 until 19) {
                    val center = geometryGrid[r][c]
                    val rect = Rect((center.x - patchSize / 2).toInt().coerceIn(0, rectifiedMat.cols() - patchSize), (center.y - patchSize / 2).toInt().coerceIn(0, rectifiedMat.rows() - patchSize), patchSize, patchSize)
                    val colorPatch = Mat(rectifiedMat, rect)
                    val edgePatch = Mat(edgeMat, rect)
                    val filenameBase = "r${r}_c${c}"
                    Imgcodecs.imwrite(File(gameFolder, "${filenameBase}_color.png").absolutePath, colorPatch)
                    Imgcodecs.imwrite(File(gameFolder, "${filenameBase}_edge.png").absolutePath, edgePatch)
                    val labelNum = when (boardLayout[r][c]) { StoneColor.EMPTY -> 0; StoneColor.BLACK -> 1; StoneColor.WHITE -> 2 }
                    csvContent.append("$filenameBase,$r,$c,$labelNum\n")
                    colorPatch.release(); edgePatch.release()
                }
            }
            File(gameFolder, "labels.csv").writeText(csvContent.toString())
        } catch (e: Exception) { Log.e("DatasetExport", "エラー", e) }
    }

    fun exportSgf(context: android.content.Context, gameRecord: GameRecord, recipientEmail: String, onFileSaved: (File) -> Unit) {
        viewModelScope.launch {
            PreferencesManager.saveEmail(context, recipientEmail)
            val currentLayout = _uiState.value.boardLayout

            val blackStones = mutableListOf<Pair<Int, Int>>()
            val whiteStones = mutableListOf<Pair<Int, Int>>()

            for (r in 0 until 19) {
                for (c in 0 until 19) {
                    when (currentLayout[r][c]) {
                        StoneColor.BLACK -> blackStones.add(Pair(c, r))
                        StoneColor.WHITE -> whiteStones.add(Pair(c, r))
                        else -> {}
                    }
                }
            }

            val updatedGameRecord = gameRecord.copy(
                initialBlackStones = blackStones,
                initialWhiteStones = whiteStones
            )

            val sgfWriter = SgfWriter(context)
            val sgfString = sgfWriter.generateSgfString(updatedGameRecord)
            val result = sgfWriter.saveSgfFileAutoNamed(sgfString)

            result.onSuccess { savedFile ->
                if (DebugConfig.isEnabled && DebugConfig.EXPORT_DATASET_PAIR) {
                    lastSourceMat?.let { src ->
                        val corners = _uiState.value.initialCorners
                        if (corners.size == 4) {
                            val rectifiedMat = BoardRectifier.rectify(src, corners)
                            val grid = createArithmeticGrid(rectifiedMat.cols().toDouble(), rectifiedMat.rows().toDouble())
                            cnnCornerDetector?.let { detector ->
                                val edgeMat = detector.generateEdgeImage(rectifiedMat)
                                exportDatasetPair(rectifiedMat, edgeMat, grid, currentLayout)
                                edgeMat.release()
                            }
                            rectifiedMat.release()
                        }
                    }
                }
                if (recipientEmail.isNotBlank()) onFileSaved(savedFile)
            }
        }
    }

    companion object {
        fun exportCroppedRectImage(edgeMat: Mat) {
            if (!DebugConfig.EXPORT_CROPPED_RECT_IMAGE) return
            try {
                val baseDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Cropped_Rect")
                val sampleDir = File(baseDir, "sample_${System.currentTimeMillis()}").apply { if (!exists()) mkdirs() }
                val resizedMat = Mat()
                Imgproc.resize(edgeMat, resizedMat, org.opencv.core.Size(256.0, 256.0))
                Imgcodecs.imwrite(File(sampleDir, "board_binary.png").absolutePath, resizedMat)
                resizedMat.release()
            } catch (e: Exception) { Log.e("CroppedRectExport", "エラー", e) }
        }

        fun saveCroppedBoardToDownload(mat: Mat) {
            if (!DebugConfig.EXPORT_ORIGINAL_BOARD_FOR_AUG) return
            try {
                val baseDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Original_Boards")
                if (!baseDir.exists()) {
                    baseDir.mkdirs()
                }

                val rgbMat = Mat()
                if (mat.channels() == 3) {
                    Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_BGR2RGB)
                } else if (mat.channels() == 4) {
                    Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_BGRA2RGBA)
                } else {
                    mat.copyTo(rgbMat)
                }

                val bmp = android.graphics.Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), android.graphics.Bitmap.Config.ARGB_8888)
                org.opencv.android.Utils.matToBitmap(rgbMat, bmp)
                rgbMat.release()

                val filename = "board_orig_${System.currentTimeMillis()}.png"
                val file = File(baseDir, filename)

                FileOutputStream(file).use { stream ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                }
                bmp.recycle()
            } catch (e: Exception) {
                Log.e("OriginalBoardExport", "エラー", e)
            }
        }
    }

    fun exportCornerImages(originalMat: Mat, corners: List<org.opencv.core.Point>, gridSpacing: Double) {
        try {
            val baseDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Cropped_Corners")
            val sampleDir = File(baseDir, "corners_${System.currentTimeMillis()}").apply { if (!exists()) mkdirs() }
            val margin = (gridSpacing * 1.5).toInt()
            corners.forEachIndexed { i, pt ->
                val roi = Rect((pt.x - margin).toInt().coerceAtLeast(0), (pt.y - margin).toInt().coerceAtLeast(0), margin * 2, margin * 2)
                val cropped = originalMat.submat(roi)
                Imgcodecs.imwrite(File(sampleDir, "corner_${i}.png").absolutePath, cropped)
                cropped.release()
            }
        } catch (e: Exception) { Log.e("CornerExport", "エラー", e) }
    }
}
