package com.github.tkirino.gobanreader.vision

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import android.util.Log
import org.opencv.imgcodecs.Imgcodecs

class GuidedBoardDetector(
    private val guideRect: Rect // UIから渡されたROI情報（画素座標系）
) {
    init {
        Log.e("DEBUG_RECT", "受け取ったRect: ${guideRect.width} x ${guideRect.height} (Pos: ${guideRect.x}, ${guideRect.y})")
    }
    // ViewModelから直接Matを受け取るように変更
    fun process(srcMat: Mat): Mat? {
        if (srcMat.empty()) return null

        // 1. 座標確認（ログ出力）
        Log.d("DEBUG_TRUTH", "Mat W:${srcMat.cols()}, H:${srcMat.rows()} | Rect X:${guideRect.x}, Y:${guideRect.y}, W:${guideRect.width}, H:${guideRect.height}")
        // 2. 盤面切り出し
        val roi = Mat(srcMat, guideRect)
        // --- ★ ここにデバッグ用の書き出しを追加 ★ ---
        Imgcodecs.imwrite("/sdcard/Download/debug_roi.png", roi)
        // ------------------------------------------
        val result = warpBoard(roi)
        roi.release()
        return result
    }

    private fun warpBoard(src: Mat): Mat {
        // 画像内の四隅を特定して変換する
        val dstSize = Size(1000.0, 1000.0)
        val srcCorners = MatOfPoint2f(
            Point(0.0, 0.0), Point(src.cols().toDouble(), 0.0),
            Point(src.cols().toDouble(), src.rows().toDouble()), Point(0.0, src.rows().toDouble())
        )
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
