package com.github.tkirino.gobanreader

import androidx.compose.ui.unit.IntSize
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import org.opencv.imgcodecs.Imgcodecs
import android.util.Log

data class GuideFrame(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
)

class GuidedBoardDetector(
    private val previewSize: IntSize,
    private val guideSizePx: Float
) {
    fun process(photoFile: File): Mat? {
        val srcMat = Imgcodecs.imread(photoFile.absolutePath)
        if (srcMat.empty()) return null

        val scaleX = srcMat.cols().toDouble() / previewSize.width.toDouble()
        val scaleY = srcMat.rows().toDouble() / previewSize.height.toDouble()
        val centerX = srcMat.cols() / 2.0
        val centerY = srcMat.rows() / 2.0
        val w = guideSizePx.toDouble() * scaleX
        val h = (guideSizePx.toDouble() * 1.073) * scaleY

        val guide = GuideFrame(
            left = centerX - (w / 2.0), top = centerY - (h / 2.0),
            right = centerX + (w / 2.0), bottom = centerY + (h / 2.0)
        )

        val corners = detectCorners(srcMat, guide) ?: return null
        val warped = warpBoard(srcMat, corners)

        // 1. まず交差点リストを作成する（これより前には置けません）
        val intersections = getIntersectionPoints(Size(1000.0, 1073.0))

        // 2. このリストを使って、まず平均輝度（AvgV）を計算する
        val allV = mutableListOf<Double>()
        for (pt in intersections) {
            val (v, _) = detectStoneAt(warped, pt.x, pt.y)
            if (v != -1.0) allV.add(v)
        }
        val boardAvgV = if (allV.isNotEmpty()) allV.average() else 128.0
        Log.d("BoardDebug", "--- AvgV: %.1f ---".format(boardAvgV))

       // 3. 次にこの AvgV を使って判定しつつログを出す
        for (pt in intersections) {
            // process内の判定ループ修正案
            val (v, s) = detectStoneAt(warped, pt.x, pt.y)
            val type = when {
                v == -2.0 -> "E" // ゲートで弾かれたら即E
                v < 70 && s < 35 -> "B"
                v > 150 && s < 60 -> "W"
                else -> "E"
            }
            Log.d("BoardDebug", "P(%3.0f,%3.0f) %s | V:%5.1f S:%5.1f".format(pt.x, pt.y, type, v, s))
        }

        return warped
    }

    private fun detectStoneAt(warped: Mat, cx: Double, cy: Double): Pair<Double, Double> {
        val R = 18

        // 1. 境界保護（クリッピング）：盤面の外にはみ出さないよう Rect を調整
        val x1 = (cx - R).toInt().coerceAtLeast(0)
        val y1 = (cy - R).toInt().coerceAtLeast(0)
        val x2 = (cx + R).toInt().coerceAtMost(warped.cols())
        val y2 = (cy + R).toInt().coerceAtMost(warped.rows())

        // 範囲が狭すぎる（端すぎて円の判定ができない）場合は Empty 判定
        if ((x2 - x1) < R || (y2 - y1) < R) {
            return Pair(-2.0, -2.0)
        }

        val roi = Mat(warped, Rect(x1, y1, x2 - x1, y2 - y1))

        // 2. 構造判定ゲート（強化版）
        val gray = Mat()
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        // 【改善点】Cannyの前に適応的な二値化を行うと、線が残りやすい
        val binary = Mat()
        Imgproc.adaptiveThreshold(gray, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV, 11, 2.0)

        // 【改善点】モルフォロジー演算でエッジを太らせて繋げる
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.dilate(binary, binary, kernel) // 膨張処理で線を繋ぐ

        val circles = Mat()
        // HoughCirclesのパラメータを大幅緩和
        // param1 (Canny): 30.0 -> 20.0
        // param2 (投票数): 20.0 -> 8.0 (かなり緩く)
        Imgproc.HoughCircles(binary, circles, Imgproc.HOUGH_GRADIENT, 1.0,
            roi.rows().toDouble(), 20.0, 8.0, 10, 25)

        if (circles.cols() == 0) {
            roi.release()
            gray.release()
            circles.release()
            return Pair(-2.0, -2.0) // 構造物なし＝Empty
        }

        // 3. 色判定アナライザー
        val hsvRoi = Mat()
        Imgproc.cvtColor(roi, hsvRoi, Imgproc.COLOR_BGR2HSV)

        val mask = Mat.zeros(roi.size(), CvType.CV_8U)
        val c = circles.get(0, 0) // 検出された円を使用
        Imgproc.circle(mask, Point(c[0], c[1]), c[2].toInt(), Scalar(255.0), -1)

        val mean = Core.mean(hsvRoi, mask)

        // リソース解放
        roi.release()
        gray.release()
        hsvRoi.release()
        mask.release()
        circles.release()

        return Pair(mean.`val`[2], mean.`val`[1]) // V, S
    }

    // --- 以下、既存の関数は変更なし ---
    private fun detectCorners(src: Mat, guide: GuideFrame): List<Point>? {
        val resized = Mat()
        val scale = 0.25
        Imgproc.resize(src, resized, Size(src.cols() * scale, src.rows() * scale))
        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_BGR2GRAY)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 20.0, 80.0)
        val lines = Mat()
        Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180, 30, 50.0, 20.0)
        val horizontalLines = mutableListOf<DoubleArray>()
        val verticalLines = mutableListOf<DoubleArray>()
        for (i in 0 until lines.rows()) {
            val l = lines.get(i, 0)
            val dx = l[2] - l[0]
            val dy = l[3] - l[1]
            val angle = Math.atan2(dy, dx) * 180 / Math.PI
            if (Math.abs(angle) < 15) horizontalLines.add(l)
            else if (Math.abs(angle - 90) < 15 || Math.abs(angle + 90) < 15) verticalLines.add(l)
        }
        val margin = 500.0
        val invScale = 1.0 / scale
        val topEdges = horizontalLines.filter { it[1] > (guide.top * scale) - margin && it[1] < (guide.top * scale) + margin }
        val bottomEdges = horizontalLines.filter { it[1] > (guide.bottom * scale) - margin && it[1] < (guide.bottom * scale) + margin }
        val leftEdges = verticalLines.filter { it[0] > (guide.left * scale) - margin && it[0] < (guide.left * scale) + margin }
        val rightEdges = verticalLines.filter { it[0] > (guide.right * scale) - margin && it[0] < (guide.right * scale) + margin }
        val topEdgeY = if (topEdges.isNotEmpty()) topEdges.map { it[1] }.average() * invScale else guide.top
        val bottomEdgeY = if (bottomEdges.isNotEmpty()) bottomEdges.map { it[1] }.average() * invScale else guide.bottom
        val leftEdgeX = if (leftEdges.isNotEmpty()) leftEdges.map { it[0] }.average() * invScale else guide.left
        val rightEdgeX = if (rightEdges.isNotEmpty()) rightEdges.map { it[0] }.average() * invScale else guide.right
        if (topEdgeY.isNaN() || bottomEdgeY.isNaN() || leftEdgeX.isNaN() || rightEdgeX.isNaN()) return null
        return listOf(Point(leftEdgeX, topEdgeY), Point(rightEdgeX, topEdgeY), Point(rightEdgeX, bottomEdgeY), Point(leftEdgeX, bottomEdgeY))
    }

    private fun warpBoard(src: Mat, corners: List<Point>): Mat {
        val sorted = sortCorners(corners)
        val srcCorners = MatOfPoint2f(*sorted.toTypedArray())
        val dstSize = Size(1000.0, 1073.0)
        val dstCorners = MatOfPoint2f(Point(0.0, 0.0), Point(1000.0, 0.0), Point(1000.0, 1073.0), Point(0.0, 1073.0))
        val transform = Imgproc.getPerspectiveTransform(srcCorners, dstCorners)
        val warped = Mat(1073, 1000, src.type())
        Imgproc.warpPerspective(src, warped, transform, dstSize)
        transform.release()
        return warped
    }

    private fun sortCorners(points: List<Point>): List<Point> {
        val topLeft = points.minByOrNull { it.x + it.y }!!
        val bottomRight = points.maxByOrNull { it.x + it.y }!!
        val topRight = points.maxByOrNull { it.x - it.y }!!
        val bottomLeft = points.minByOrNull { it.x - it.y }!!
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    fun getIntersectionPoints(warpedSize: Size): List<Point> {
        val points = mutableListOf<Point>()
        // 19路盤なので、0〜18の計19個の交点を配置する
        val intervalW = warpedSize.width / 18.0
        val intervalH = warpedSize.height / 18.0
        for (i in 0..18) {
            for (j in 0..18) {
                points.add(Point(i * intervalW, j * intervalH))
            }
        }
        return points
    }

}
