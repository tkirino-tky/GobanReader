package com.github.tkirino.gobanreader.vision

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import org.opencv.imgcodecs.Imgcodecs

data class GuideFrame(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
)

class GuidedBoardDetector(
    private val guideRect: Rect // UIから渡されたROI情報（画素座標系）
) {
    fun process(photoFile: File): Mat? {
        val srcMat = Imgcodecs.imread(photoFile.absolutePath)
        if (srcMat.empty()) return null

        // OpenCVの行列座標系で直接切り出す
        // guideRect は Imgproc 等が期待する OpenCV の Rect と互換性があります
        val roi = Mat(srcMat, guideRect)

        // ここで ROI に対して透視変換や直線抽出を行い、盤面を warp する
        // 低レベルなスケーリング計算は一切行いません
        val warped = warpBoard(roi)

        return warped
    }

    private fun warpBoard(src: Mat): Mat {
        // 画像内の四隅を特定（ここもOpenCVの処理のみ）
        // とりあえずサンプルの通り、ガイドの端を四隅とみなして変換する
        val dstSize = Size(1000.0, 1073.0)
        val srcCorners = MatOfPoint2f(
            Point(0.0, 0.0), Point(src.cols().toDouble(), 0.0),
            Point(src.cols().toDouble(), src.rows().toDouble()), Point(0.0, src.rows().toDouble())
        )
        val dstCorners = MatOfPoint2f(
            Point(0.0, 0.0), Point(1000.0, 0.0),
            Point(1000.0, 1073.0), Point(0.0, 1073.0)
        )

        val transform = Imgproc.getPerspectiveTransform(srcCorners, dstCorners)
        val warped = Mat(1073, 1000, src.type())
        Imgproc.warpPerspective(src, warped, transform, dstSize)
        transform.release()

        return warped
    }
}
