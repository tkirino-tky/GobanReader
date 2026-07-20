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

        // 5. 精緻化をバイパスし、近似された生コーナーをそのまま使用する
        val contour2f = MatOfPoint2f(*boardContour.toArray())
        val peri = Imgproc.arcLength(contour2f, true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)
        val roughCorners = approx.toList()

        // 5. 精緻化をバイパスし、近似された生コーナーをそのまま使用する（一時的テスト用）
        // テストのために改変　後に削除し、下のコメントアウトの部分を復活
        gray.release()
        blurred.release()
        edged.release()
        hierarchy.release()

        // roughCornersが十分に得られているかチェックして返す
        return if (roughCorners.size != 4) {
            DetectionResult(emptyList(), false)
        } else {
            DetectionResult(roughCorners, true)
        }

     // 5. 精緻化をバイパスし、近似された生コーナーをそのまま使用する（一時的テスト用）
     //   val refinedCorners = refinerResult.refinedCorners
     //   return if (refinedCorners.any { it == null }) {
     //       DetectionResult(emptyList(), false)
     //   } else {
     //       DetectionResult(refinedCorners.filterNotNull(), true)
     //   }
    }
}
