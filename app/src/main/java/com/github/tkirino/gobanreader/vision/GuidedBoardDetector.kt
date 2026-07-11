package com.github.tkirino.gobanreader.vision

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import android.util.Log

class GuidedBoardDetector(
    private val guideRect: Rect
) {
    private val detector = CornerLineDetector()

    fun detectCorners(croppedMat: Mat): List<Point>? {
        if (croppedMat.empty()) return null

        val bandWidth = (croppedMat.cols() / 10).coerceIn(40, 100)
        val rois = listOf(
            Rect(0, 0, croppedMat.cols(), bandWidth),                          // 辺 0: 上辺
            Rect(0, croppedMat.rows() - bandWidth, croppedMat.cols(), bandWidth), // 辺 1: 下辺
            Rect(0, 0, bandWidth, croppedMat.rows()),                          // 辺 2: 左辺
            Rect(croppedMat.cols() - bandWidth, 0, bandWidth, croppedMat.rows())  // 辺 3: 右辺
        )

        val results = detector.detectCornerLines(croppedMat, rois)

        val lineTop = results[0].detectedLine
        val lineBottom = results[1].detectedLine
        val lineLeft = results[2].detectedLine
        val lineRight = results[3].detectedLine

        if (lineTop == null || lineBottom == null || lineLeft == null || lineRight == null) {
            Log.w("GuidedBoardDetector", "いずれかの辺の直線検出に失敗したため、交点を計算できません。")
            return null
        }

        val topLeft = computeIntersection(lineTop, lineLeft)
        val topRight = computeIntersection(lineTop, lineRight)
        val bottomRight = computeIntersection(lineBottom, lineRight)
        val bottomLeft = computeIntersection(lineBottom, lineLeft)

        if (topLeft == null || topRight == null || bottomRight == null || bottomLeft == null) {
            Log.w("GuidedBoardDetector", "直線の交点計算に失敗しました（平行な関係など）。")
            return null
        }

        Log.d("GuidedBoardDetector", "四隅の座標導出に成功: TL=$topLeft, TR=$topRight, BR=$bottomRight, BL=$bottomLeft")

        // 順番は非常に重要：左上、右上、右下、左下の順
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun computeIntersection(l1: Line, l2: Line): Point? {
        val dX1 = l1.x2 - l1.x1
        val dY1 = l1.y2 - l1.y1
        val dX2 = l2.x2 - l2.x1
        val dY2 = l2.y2 - l2.y1

        val denominator = dX1 * dY2 - dY1 * dX2

        if (Math.abs(denominator) < 1e-5) {
            return null
        }

        val t1 = ((l2.x1 - l1.x1) * dY2 - (l2.y1 - l1.y1) * dX2) / denominator

        val x = l1.x1 + t1 * dX1
        val y = l1.y1 + t1 * dY1

        return Point(x, y)
    }

    // 引数に計算済みの四隅 (corners) を受け取るように修正
    fun warpBoard(src: Mat, corners: List<Point>): Mat {
        val dstSize = Size(1000.0, 1000.0)

        // 計算で求めた四隅を、変形元の頂点として指定
        val srcCorners = MatOfPoint2f(corners[0], corners[1], corners[2], corners[3])

        // 変形先の頂点（1000x1000の正方形）
        val dstCorners = MatOfPoint2f(
            Point(0.0, 0.0), Point(1000.0, 0.0),
            Point(1000.0, 1000.0), Point(0.0, 1000.0)
        )

        val transform = Imgproc.getPerspectiveTransform(srcCorners, dstCorners)
        val warped = Mat(1000, 1000, src.type())
        Imgproc.warpPerspective(src, warped, transform, dstSize)

        transform.release()
        srcCorners.release()
        dstCorners.release()

        return warped
    }
}
