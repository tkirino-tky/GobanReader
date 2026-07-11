package com.github.tkirino.gobanreader.vision

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.core.times
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

// 碁盤の直線検出用設定
data class DetectionConfig(
    val claheClipLimit: Double = 2.0,
    val cannyLowerRatio: Double = 0.5,
    val cannyUpperRatio: Double = 1.5,
    val houghThreshold: Int = 40,
    val houghMinLineLength: Double = 60.0,
    val houghMaxLineGap: Double = 25.0
)

class CornerLineDetector(private val config: DetectionConfig = DetectionConfig()) {

    data class CornerResult(val roi: Rect, val detectedLine: Line?)

    // ROIリストを受け取り、それぞれから直線を抽出する
    fun detectCornerLines(src: Mat, roiList: List<Rect>): List<CornerResult> {
        return roiList.map { roi ->
            CornerResult(roi, extractBestLineFromRoi(src, roi))
        }
    }

    private fun extractBestLineFromRoi(src: Mat, roi: Rect): Line? {
        val matRoi = Mat(src, roi)
        val edges = preprocessAndDetectEdges(matRoi)

        val linesMat = Mat()
        Imgproc.HoughLinesP(edges, linesMat, 1.0, PI / 180, config.houghThreshold, config.houghMinLineLength, config.houghMaxLineGap)

        val candidates = mutableListOf<Line>()
        for (i in 0 until linesMat.rows()) {
            val data = linesMat.get(i, 0)
            candidates.add(Line(data[0] + roi.x, data[1] + roi.y, data[2] + roi.x, data[3] + roi.y))
        }

        // ロジック：ROIの場所に応じた境界優先評価
        val best = candidates.maxByOrNull { line ->
            val midX = (line.x1 + line.x2) / 2.0
            val midY = (line.y1 + line.y2) / 2.0

            // ROIの端（左, 上, 右, 下）への距離
            val distToLeft = midX - roi.x
            val distToRight = (roi.x + roi.width) - midX
            val distToTop = midY - roi.y
            val distToBottom = (roi.y + roi.height) - midY

            // 現在処理中のROIが画像全体のどこにあるかで優先境界を決定
            val minDistToBoundary = when {
                roi.y > (src.height() / 2) -> distToBottom // 下端ROI
                roi.y < (src.height() / 2) -> distToTop    // 上端ROI
                else -> Math.min(distToLeft, distToRight) // 左右ROI
            }

            // スコア = 長さ * 5 - 境界距離 * 10
            // 境界に近い線ほど高く評価される
            (line.length * 5.0) - (minDistToBoundary * 10.0)
        }

        if (best != null) {
            val midX = (best.x1 + best.x2) / 2.0
            val midY = (best.y1 + best.y2) / 2.0
            // 境界までの距離をログ出力（デバッグ用）
            val distToBoundary = when {
                roi.y > (src.height() / 2) -> (roi.y + roi.height) - midY
                roi.y < (src.height() / 2) -> midY - roi.y
                else -> Math.min(midX - roi.x, (roi.x + roi.width) - midX)
            }
            Log.d("CornerLineDetector", "採用線: 長さ=${best.length.toInt()}, 距離:${distToBoundary.toInt()}")
        }
        return best
    }
    private fun preprocessAndDetectEdges(roi: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY)

        val clahe = Imgproc.createCLAHE(config.claheClipLimit, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)

        val blurred = Mat()
        Imgproc.GaussianBlur(enhanced, blurred, Size(5.0, 5.0), 0.0)

        // 【ここを修正】Core.mean()の結果を明示的にKotlinのDoubleとして取得する
        val meanVal = Core.mean(blurred).`val`[0]
        val meanDouble = meanVal as Double

        // これで meanDouble に対して算術演算が可能になります
        var lower = meanDouble * config.cannyLowerRatio
        if (lower < 10.0) lower = 10.0

        var upper = meanDouble * config.cannyUpperRatio
        if (upper > 255.0) upper = 255.0

        val edges = Mat()
        Imgproc.Canny(blurred, edges, lower, upper)
        return edges
    }
}

// これを CornerLineDetector.kt の末尾（クラスの外）に追記してください
// CornerLineDetector.kt 内の Line クラスを以下に差し替えてください
data class Line(
    val x1: Double, val y1: Double,
    val x2: Double, val y2: Double
) {
    val length get() = hypot(x2 - x1, y2 - y1)

    // 角度計算プロパティを追加
    val angleDeg: Double get() {
        val rad = atan2(y2 - y1, x2 - x1)
        val deg = Math.toDegrees(rad)
        return if (deg < 0) deg + 180 else deg
    }
}

