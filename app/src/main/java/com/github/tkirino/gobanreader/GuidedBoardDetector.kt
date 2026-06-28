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

        // 判定処理：V(輝度)とS(彩度)を確認するためのログ出力
        val intersections = getIntersectionPoints(Size(1000.0, 1073.0))
        for (pt in intersections) {
            val (v, s) = detectStoneAt(warped, pt.x, pt.y)
            if (v != -1.0) {
                Log.d("BoardDebug", "Point(${pt.x.toInt()}, ${pt.y.toInt()}) V: ${"%.1f".format(v)}, S: ${"%.1f".format(s)}")
            }
        }

        return warped
    }

    private fun detectStoneAt(warped: Mat, cx: Double, cy: Double): Pair<Double, Double> {
        val R = 12
        val rect = Rect((cx - R).toInt(), (cy - R).toInt(), R * 2, R * 2)

        if (rect.x < 0 || rect.y < 0 || rect.x + rect.width > warped.cols() || rect.y + rect.height > warped.rows()) {
            return Pair(-1.0, -1.0)
        }

        val roi = Mat(warped, rect)
        val hsvRoi = Mat()
        Imgproc.cvtColor(roi, hsvRoi, Imgproc.COLOR_BGR2HSV)

        val mask = Mat.zeros(roi.size(), CvType.CV_8U)
        Imgproc.circle(mask, Point(R.toDouble(), R.toDouble()), R, Scalar(255.0), -1)

        val mean = Core.mean(hsvRoi, mask)

        roi.release()
        hsvRoi.release()
        mask.release()

        // V: 明度(2), S: 彩度(1)
        return Pair(mean.`val`[2], mean.`val`[1])
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
        val centerX = warpedSize.width / 2.0
        val centerY = warpedSize.height / 2.0
        val lineIntervalW = warpedSize.width / 18.0
        val lineIntervalH = warpedSize.height / 18.0
        for (i in -9..9) {
            for (j in -9..9) {
                points.add(Point(centerX + (i * lineIntervalW), centerY + (j * lineIntervalH)))
            }
        }
        return points
    }
}
