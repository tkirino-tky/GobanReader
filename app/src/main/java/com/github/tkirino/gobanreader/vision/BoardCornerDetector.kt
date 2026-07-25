package com.github.tkirino.gobanreader.vision

import android.content.Context
import com.github.tkirino.gobanreader.utility.DebugUtility
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class BoardCornerDetector {
    // 戻り値用のデータクラス
    data class DetectionResult(
        val corners: List<Point>,
        val found: Boolean
    )

    fun detect(context: Context, input: Mat): DetectionResult {
        // 1. グレースケール変換
        val gray = Mat()
        if (input.channels() == 1) {
            input.copyTo(gray)
        } else {
            Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)
        }

        // 2. 二値化処理
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        val edged = Mat()
        Imgproc.Canny(blurred, edged, 50.0, 150.0)
        Imgproc.dilate(edged, edged, Mat(), Point(-1.0, -1.0), 2)

        DebugUtility.saveDebugImage(context, edged, "corner_edged")

        // 3. 輪郭検出
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edged, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val boardContour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: run {
            gray.release(); blurred.release(); edged.release(); hierarchy.release()
            return DetectionResult(emptyList(), false)
        }

        // 4. 4つの頂点に近似
        val contour2f = MatOfPoint2f(*boardContour.toArray())
        val peri = Imgproc.arcLength(contour2f, true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)
        val roughCorners = approx.toList()

        gray.release()
        blurred.release()
        edged.release()
        hierarchy.release()

        if (roughCorners.size != 4) {
            return DetectionResult(emptyList(), false)
        }

        // ★ 5. 検出された4隅の順序を「左上、右上、右下、左下」に確実に並び替える（正規化）
        val sortedCorners = sortCorners(roughCorners)

        return DetectionResult(sortedCorners, true)
    }

    /**
     * 4つのコーナー座標を [左上, 右上, 右下, 左下] の順にソートするヘルパー関数
     */
    /**/
    private fun sortCorners(corners: List<Point>): List<Point> {
        val sortedByY = corners.sortedBy { it.y }
        // 上側の2点と下側の2点に分ける
        val topPoints = sortedByY.take(2).sortedBy { it.x }
        val bottomPoints = sortedByY.drop(2).sortedBy { it.x }

        val topLeft = topPoints.first()       // Xが小さい方が左上
        val topRight = topPoints.last()       // Xが大きい方が右上
        val bottomRight = bottomPoints.last() // Xが大きい方が右下
        val bottomLeft = bottomPoints.first() // Xが小さい方が左下

        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }
}
