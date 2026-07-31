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

    private fun createArithmeticGrid(width: Double, height: Double, boardMat: Mat? = null): Array<Array<Point>> {
        // 1. 基本ステップ幅の算出
        val stepX = width / 19.0
        val stepY = height / 19.0

        // 2. 中央の交点（10路目、インデックス9）を基準点とする
        val centerIndex = 9.0
        val baseCenterX = (centerIndex + 0.5) * stepX
        val baseCenterY = (centerIndex + 0.5) * stepY

        // 3. ズレ（オフセット）の初期値
        var offsetX = 0.0
        var offsetY = 0.0

        // 4. 画像データ（boardMat）が利用可能な場合、中央付近の空白交点をぐるりと探して微調整量を計算する
        if (boardMat != null) {
            val detectedOffset = estimateGridOffset(boardMat, stepX, stepY)
            if (detectedOffset != null) {
                offsetX = detectedOffset.first
                offsetY = detectedOffset.second
                Log.d("GridAdjustment", "検出されたオフセット -> offsetX: $offsetX, offsetY: $offsetY")
            }
        }

        // 5. 中央を基準（インデックス9からの相対距離）として全19路の座標を生成
        return Array(19) { r ->
            Array(19) { c ->
                val x = baseCenterX + (c - centerIndex) * stepX + offsetX
                val y = baseCenterY + (r - centerIndex) * stepY + offsetY
                Point(x, y)
            }
        }
    }

    /**
     * 中央付近から空いている交差点（十字）を探し、理論位置からのズレを検出する
     */
    private fun estimateGridOffset(boardMat: Mat, stepX: Double, stepY: Double): Pair<Double, Double>? {
        // 中央の9,9を中心に、ぐるりと周辺の空白になりやすい候補を探索
        val searchCenters = listOf(
            Pair(9, 9), Pair(9, 8), Pair(8, 9), Pair(8, 8),
            Pair(9, 10), Pair(10, 9), Pair(10, 10), Pair(8, 10), Pair(10, 8),
            Pair(7, 9), Pair(9, 7), Pair(11, 9), Pair(9, 11)
        )

        val patchSize = 40 // 切り出すパッチのサイズ

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
                // パッチ内で十字線の本当の中心位置（最も暗い交点）を探す
                val localOffset = findExactIntersectionInPatch(patch, patchSize)
                patch.release()

                if (localOffset != null) {
                    // パッチ内座標から全体座標でのズレ（dx, dy）を計算
                    val exactX = x1 + localOffset.first
                    val exactY = y1 + localOffset.second
                    val dx = exactX - roughX
                    val dy = exactY - roughY

                    // 妥当なズレの範囲内（ステップの30%以内）であれば採用
                    if (Math.abs(dx) < stepX * 0.3 && Math.abs(dy) < stepY * 0.3) {
                        return Pair(dx, dy)
                    }
                }
            }
        }
        return null
    }

    /**
     * 切り出したパッチ画像から、罫線の交点（十字の中心）の正確なローカル位置を特定する
     */
    private fun findExactIntersectionInPatch(patch: Mat, patchSize: Int): Pair<Double, Double>? {
        val gray = Mat()
        if (patch.channels() > 1) {
            Imgproc.cvtColor(patch, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            patch.copyTo(gray)
        }

        // 縦方向・横方向の投影（プロファイル）を取ることで、線の中心（輝度が最も低くなるライン）を正確に見つける
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

        // 最も暗い（線がある）インデックスを求める
        var minX = -1.0
        var minY = -1.0
        var minRowVal = Double.MAX_VALUE
        var minColVal = Double.MAX_VALUE

        // 中央付近（パッチ全体の1/4〜3/4の範囲）で最も暗い位置を探索
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

        // 十字の交点として十分な濃さ（線）が検出できているか簡易チェック
        if (minX >= 0 && minY >= 0) {
            return Pair(minX, minY)
        }
        return null
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
