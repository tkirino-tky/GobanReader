package com.github.tkirino.gobanreader.stones

import android.util.Log
import com.github.tkirino.gobanreader.model.StoneColor
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.io.FileWriter
class StoneDetector(
    private val sampleRadius: Int = 12 // 交点からサンプリングする半径（ピクセル）
) {

    companion object {
        private const val TAG = "StoneDetector"
    }

    /**
     * 361路の交点グリッドと画像を受け取り、各交点の石の状態（EMPTY, BLACK, WHITE）を判定する
     */
    fun detectStones(rectifiedMat: Mat, geometryGrid: Array<Array<Point>>): List<List<StoneColor>> {
        // 1. カラー画像を Lab 色空間に変換
        val labMat = Mat()
        Imgproc.cvtColor(rectifiedMat, labMat, Imgproc.COLOR_BGR2Lab)

        // グレースケール画像も白黒判定用に準備
        val grayMat = Mat()
        Imgproc.cvtColor(rectifiedMat, grayMat, Imgproc.COLOR_BGR2GRAY)

        // 2. 罫線確認用のエッジ画像（二値化画像）を生成
        val edgeMat = Mat()
        val blurred = Mat()
        Imgproc.GaussianBlur(grayMat, blurred, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(blurred, edgeMat, 50.0, 150.0)
        Imgproc.dilate(edgeMat, edgeMat, Mat(), Point(-1.0, -1.0), 2)

        val boardLayout = MutableList(19) { MutableList(19) { StoneColor.EMPTY } }

        data class IntersectionFeature(
            val row: Int,
            val col: Int,
            val grayMedian: Double,
            val isEmptyByColor: Boolean,
            val hasGridLine: Boolean
        )

        val stoneFeatures = mutableListOf<IntersectionFeature>()

        // 3. 各交点のサンプリングと判定
        for (row in 0 until 19) {
            for (col in 0 until 19) {
                val pt = geometryGrid[row][col]
                val roi = getRoiRect(pt, sampleRadius, labMat.cols(), labMat.rows())

                var isEmptyByColor = true
                var grayMedian = 0.0

                if (roi.width > 0 && roi.height > 0) {
                    val labRoi = labMat.submat(roi)
                    val grayRoi = grayMat.submat(roi)

                    val aMedian = calculateChannelMedian(labRoi, 1)
                    val bMedian = calculateChannelMedian(labRoi, 2)
                    grayMedian = calculateChannelMedian(grayRoi, 0)

                    // a, b チャンネルがほぼ 0 (無彩色) なら石、偏っていれば木目（空き地）
                    val isChromatic = abs(aMedian - 128.0) > 8.0 || abs(bMedian - 128.0) > 12.0
                    isEmptyByColor = isChromatic

                    labRoi.release()
                    grayRoi.release()
                }

                // エッジ画像からこの交点に罫線が存在するかをチェック
                val hasGridLine = checkGridLineAt(edgeMat, pt, row, col)

                // 4. 罫線と色情報の整合性をチェックし、LogCatに出力（矛盾の検証）
                if (hasGridLine && !isEmptyByColor) {
                    Log.d(TAG, "[$row, $col] 矛盾検知: 罫線はあるが色情報は石と判定 (光飛び等の可能性)")
                }

                // 基本方針: 罫線がしっかり通っている場所は、光飛びに関わらず「空き地(EMPTY)」とする
                // 罫線がない（石に遮られている）場所を石候補とする
                if (hasGridLine) {
                    boardLayout[row][col] = StoneColor.EMPTY
                } else {
                    stoneFeatures.add(IntersectionFeature(row, col, grayMedian, isEmptyByColor, hasGridLine))
                }
            }
        }

        // 5. 石候補（黒石・白石）をグレースケールの明るさで二分する
        // 5. 石候補（黒石・白石）をグレースケールの明るさで二分する
        if (stoneFeatures.isNotEmpty()) {
            val brightnessList = stoneFeatures.map { it.grayMedian }.sorted()
            android.util.Log.d("GobanReader", "Stone Brightness Distribution (Count: ${stoneFeatures.size}): $brightnessList")

            var centerBlack = stoneFeatures.minOf { it.grayMedian }.toFloat()
            var centerWhite = stoneFeatures.maxOf { it.grayMedian }.toFloat()

            if (centerBlack == centerWhite) {
                centerWhite += 1.0f
            }

            val maxIterations = 10
            val blackGroup = mutableListOf<IntersectionFeature>()
            val whiteGroup = mutableListOf<IntersectionFeature>()

            for (i in 0 until maxIterations) {
                blackGroup.clear()
                whiteGroup.clear()

                for (sf in stoneFeatures) {
                    val medianVal = sf.grayMedian.toFloat()
                    val distToBlack = kotlin.math.abs(medianVal - centerBlack)
                    val distToWhite = kotlin.math.abs(medianVal - centerWhite)

                    if (distToBlack < distToWhite) {
                        blackGroup.add(sf)
                    } else {
                        whiteGroup.add(sf)
                    }
                }

                val newCenterBlack = if (blackGroup.isNotEmpty()) {
                    blackGroup.map { it.grayMedian }.average().toFloat()
                } else {
                    centerBlack
                }

                val newCenterWhite = if (whiteGroup.isNotEmpty()) {
                    whiteGroup.map { it.grayMedian }.average().toFloat()
                } else {
                    centerWhite
                }

                val diffBlack = kotlin.math.abs(newCenterBlack - centerBlack)
                val diffWhite = kotlin.math.abs(newCenterWhite - centerWhite)

                if (diffBlack < 0.1f && diffWhite < 0.1f) {
                    centerBlack = newCenterBlack
                    centerWhite = newCenterWhite
                    break
                }

                centerBlack = newCenterBlack
                centerWhite = newCenterWhite
            }

            val darkerGroup: List<IntersectionFeature>
            val lighterGroup: List<IntersectionFeature>

            if (centerBlack < centerWhite) {
                darkerGroup = blackGroup
                lighterGroup = whiteGroup
            } else {
                darkerGroup = whiteGroup
                lighterGroup = blackGroup
            }

            for (sf in darkerGroup) {
                boardLayout[sf.row][sf.col] = StoneColor.BLACK
            }
            for (sf in lighterGroup) {
                boardLayout[sf.row][sf.col] = StoneColor.WHITE
            }

            android.util.Log.d("GobanReader", "Classification completed: BLACK=${darkerGroup.size} stones, WHITE=${lighterGroup.size} stones")
        }

        labMat.release()
        grayMat.release()
        edgeMat.release()
        blurred.release()

        return boardLayout.map { it.toList() }
    }

    private fun getRoiRect(center: Point, radius: Int, maxCols: Int, maxRows: Int): Rect {
        val x = (center.x - radius).toInt().coerceIn(0, maxCols)
        val y = (center.y - radius).toInt().coerceIn(0, maxRows)
        val x2 = (center.x + radius).toInt().coerceIn(0, maxCols)
        val y2 = (center.y + radius).toInt().coerceIn(0, maxRows)
        return Rect(x, y, x2 - x, y2 - y)
    }

    private fun calculateChannelMedian(mat: Mat, channel: Int): Double {
        val channels = ArrayList<Mat>()
        Core.split(mat, channels)
        val chMat = channels[channel]

        val sz = chMat.total().toInt()
        val buff = ByteArray(sz)
        chMat.get(0, 0, buff)
        val sorted = buff.map { it.toInt() and 0xFF }.sorted()
        val median = if (sorted.isNotEmpty()) sorted[sorted.size / 2].toDouble() else 0.0

        chMat.release()
        channels.forEach { it.release() }
        return median
    }

    /**
     * 交点の位置（中央・辺・角）に応じて、エッジ画像上に適切な罫線が存在するかを検査する
     */
    private fun checkGridLineAt(edgeMat: Mat, pt: Point, row: Int, col: Int): Boolean {
        val x = pt.x.toInt()
        val y = pt.y.toInt()
        val checkLen = 8

        if (x - checkLen < 0 || x + checkLen >= edgeMat.cols() ||
            y - checkLen < 0 || y + checkLen >= edgeMat.rows()) {
            return false
        }

        val isTop = (row == 0)
        val isBottom = (row == 18)
        val isLeft = (col == 0)
        val isRight = (col == 18)

        // 4隅の判定 (L字) - 条件を大幅に緩和（1ドットでも検知されればOKにする）
        if ((isTop || isBottom) && (isLeft || isRight)) {
            var hLine = false
            var vLine = false
            val dx = if (isLeft) 1 else -1
            val dy = if (isTop) 1 else -1

            for (i in 2..checkLen) {
                if (edgeMat.get(y, x + i * dx)[0] > 128.0) hLine = true
                if (edgeMat.get(y + i * dy, x)[0] > 128.0) vLine = true
            }
            // どちらか一方が見つかる、あるいは少しでも通っていれば許可するなど、必要に応じてさらに緩和可能
            return hLine || vLine
        }

        // 4辺の判定 (T字) - 閾値を大幅に引き下げ
        if (isTop || isBottom || isLeft || isRight) {
            var parallelCount = 0
            var verticalLine = false

            if (isTop || isBottom) {
                for (i in -checkLen..checkLen) {
                    if (edgeMat.get(y, x + i)[0] > 128.0) parallelCount++
                }
                val dy = if (isTop) 1 else -1
                for (i in 2..checkLen) {
                    if (edgeMat.get(y + i * dy, x)[0] > 128.0) verticalLine = true
                }
            } else {
                for (i in -checkLen..checkLen) {
                    if (edgeMat.get(y + i, x)[0] > 128.0) parallelCount++
                }
                val dx = if (isLeft) 1 else -1
                for (i in 2..checkLen) {
                    if (edgeMat.get(y, x + i * dx)[0] > 128.0) verticalLine = true
                }
            }
            // 以前は parallelLines >= 4 && verticalLine だったのを、parallelCount >= 1 などに緩和
            return parallelCount >= 1 || verticalLine
        }

        // 中央部の判定 (十字) - 現状維持
        var hCount = 0
        var vCount = 0
        for (i in -checkLen..checkLen) {
            if (edgeMat.get(y, x + i)[0] > 128.0) hCount++
            if (edgeMat.get(y + i, x)[0] > 128.0) vCount++
        }

        return hCount >= 5 && vCount >= 5
    }

    /**
     * 361交点のパッチ画像（32x32）とメタデータを一括出力するメソッド
     * @param rectifiedMat 補正済みの盤面画像
     * @param geometryGrid 19x19の交点座標グリッド
     * @param boardLayout detectStones等で得られた判定結果レイアウト
     * @param outputDir 出力先のディレクトリ（例: 外部ストレージやアプリの専用ディレクトリ）
     * @param gameId 盤面や対局を識別するID（ファイル名の重複を防ぐため、タイムスタンプ等）
     */
    fun exportDataset(
        rectifiedMat: Mat,
        geometryGrid: Array<Array<Point>>,
        boardLayout: List<List<StoneColor>>,
        outputDir: File,
        gameId: String
    ) {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        // CSVファイルのパス
        val csvFile = File(outputDir, "dataset_metadata_${gameId}.csv")

        // CSVに書き出し（append: true で追記モード）
        FileWriter(csvFile, true).use { writer ->
            // 必要に応じてヘッダーを出力（必要であればコメントアウトを解除してください）
            // writer.append("filename,row,col,label\n")

            for (row in 0 until 19) {
                for (col in 0 until 19) {
                    val pt = geometryGrid[row][col]
                    // 32x32ピクセルで切り出すための半径16のROI（必要に応じて調整可能）
                    val roi = getRoiRect(pt, 16, rectifiedMat.cols(), rectifiedMat.rows())

                    if (roi.width > 0 && roi.height > 0) {
                        val patchMat = rectifiedMat.submat(roi)

                        // 確実に32x32サイズにリサイズ
                        val resizedPatch = Mat()
                        Imgproc.resize(patchMat, resizedPatch, Size(32.0, 32.0))

                        // 保存する画像ファイル名
                        val filename = "patch_${gameId}_r${row}_c${col}.png"
                        val file = File(outputDir, filename)
                        Imgcodecs.imwrite(file.absolutePath, resizedPatch)

                        // ラベル表現 (EMPTY: 0, BLACK: 1, WHITE: 2)
                        val labelVal = when (boardLayout[row][col]) {
                            StoneColor.EMPTY -> 0
                            StoneColor.BLACK -> 1
                            StoneColor.WHITE -> 2
                        }

                        // 【変更点】2列目に行番号(row)、3列目に列番号(col)を配置するように修正
                        // 並び: filename, row, col, label
                        writer.append("$filename,$row,$col,$labelVal\n")

                        patchMat.release()
                        resizedPatch.release()
                    }
                }
            }
        }
        Log.d(TAG, "Dataset exported successfully for gameId: $gameId to ${outputDir.absolutePath}")
    }
}
