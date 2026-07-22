package com.github.tkirino.gobanreader.stones

import com.github.tkirino.gobanreader.model.StoneColor
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

class StoneDetector(
    private val sampleRadius: Int = 12 // 交点からサンプリングする半径（ピクセル）
) {
    data class IntersectionFeature(
        val row: Int,
        val col: Int,
        val l: Double,
        val a: Double,
        val b: Double,
        val grayMedian: Double,
        val darkRatio: Double, // 領域内の「黒い罫線成分」の割合（低明度ピクセルの比率）
        var isEmpty: Boolean = false
    )

    /**
     * 361路の交点グリッドと画像を受け取り、各交点の石の状態（EMPTY, BLACK, WHITE）を判定する
     */
    fun detectStones(rectifiedMat: Mat, geometryGrid: Array<Array<Point>>): List<List<StoneColor>> {
        val labMat = Mat()
        Imgproc.cvtColor(rectifiedMat, labMat, Imgproc.COLOR_BGR2Lab)

        val grayMat = Mat()
        Imgproc.cvtColor(rectifiedMat, grayMat, Imgproc.COLOR_BGR2GRAY)

        val boardLayout = MutableList(19) { MutableList(19) { StoneColor.EMPTY } }
        val features = mutableListOf<IntersectionFeature>()

        // 1. 各交点のサンプリングと特徴抽出（明度、色彩、および黒線成分の割合）
        for (row in 0 until 19) {
            for (col in 0 until 19) {
                val pt = geometryGrid[row][col]
                val roi = getRoiRect(pt, sampleRadius, grayMat.cols(), grayMat.rows())

                if (roi.width > 0 && roi.height > 0) {
                    val labRoi = labMat.submat(roi)
                    val grayRoi = grayMat.submat(roi)

                    val lMedian = calculateChannelMedian(labRoi, 0)
                    val aMedian = calculateChannelMedian(labRoi, 1)
                    val bMedian = calculateChannelMedian(labRoi, 2)
                    val grayMedian = calculateChannelMedian(grayRoi, 0)

                    // 領域内における「黒い罫線（低明度）」のピクセル割合を計算
                    val darkRatio = calculateDarkPixelRatios(grayRoi, grayMedian)

                    features.add(IntersectionFeature(row, col, lMedian, aMedian, bMedian, grayMedian, darkRatio))

                    labRoi.release()
                    grayRoi.release()
                }
            }
        }

        if (features.isNotEmpty()) {
            // =========================================================================
            // ステップ1：罫線（黒線）の有無による空き地の確定
            // ※ 空き地には必ず交差する黒い罫線があるため、低明度成分が一定以上存在する。
            //   石（黒・白）で覆われている場所にはこの罫線が隠れて消滅する。
            // =========================================================================
            for (f in features) {
                // 明らかな色彩の偏り（木目カラー）または、十分な黒線成分がある場合は空き地とする
                val isWoodColor = abs(f.a - 128.0) > 9.0 || abs(f.b - 128.0) > 12.0

                // 罫線の黒成分がしっかり検知できる＝空き地
                // （※黒石の上では黒成分が全体に広がるか、逆に白石の上では消えるため、適切な閾値で判定）
                val hasGridLine = f.darkRatio > 0.08 // 領域内で黒い線が占める割合の閾値（要調整）

                if (isWoodColor || hasGridLine) {
                    f.isEmpty = true
                }
            }

            // ステップ1で空き地と確定したものをボードに反映
            for (f in features) {
                if (f.isEmpty) {
                    boardLayout[f.row][f.col] = StoneColor.EMPTY
                }
            }

            // =========================================================================
            // ステップ2：残った「石の候補」の分離（黒と白）
            // =========================================================================
            val stoneCandidates = features.filter { !it.isEmpty }

            if (stoneCandidates.isNotEmpty()) {
                val sortedByBrightness = stoneCandidates.sortedBy { it.grayMedian }
                val medianIndex = sortedByBrightness.size / 2

                for ((index, sc) in sortedByBrightness.withIndex()) {
                    val color = if (index < medianIndex) StoneColor.BLACK else StoneColor.WHITE
                    boardLayout[sc.row][sc.col] = color
                }
            }
        }

        labMat.release()
        grayMat.release()

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

        channels.forEach { it.release() }
        return median
    }

    /**
     * 領域内の全体的な明るさ（中央値）よりも明らかに暗いピクセル（＝黒い罫線）の割合を計算する
     */
    private fun calculateDarkPixelRatios(grayRoi: Mat, medianGray: Double): Double {
        val sz = grayRoi.total().toInt()
        if (sz == 0) return 0.0
        val buff = ByteArray(sz)
        grayRoi.get(0, 0, buff)

        // 木目の背景よりも十分に暗い（罫線とみなせる）閾値を設定
        // 例: 領域の中央値よりも 35 以上暗いピクセルを「黒線成分」とする
        val threshold = (medianGray - 35.0).coerceAtLeast(0.0)

        var darkCount = 0
        for (b in buff) {
            val v = b.toInt() and 0xFF
            if (v < threshold) {
                darkCount++
            }
        }
        return darkCount.toDouble() / sz.toDouble()
    }
}
