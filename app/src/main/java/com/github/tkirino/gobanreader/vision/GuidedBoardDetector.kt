package com.github.tkirino.gobanreader.vision

import android.content.Context
import android.util.Log
import com.github.tkirino.gobanreader.vision.GridLineDetector
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class GuidedBoardDetector(
    private val context: Context,
    private val guideRect: Rect
) {
    // 新しい検出器をインスタンス化
    private val gridDetector = GridLineDetector()

    fun detectCorners(croppedMat: Mat): List<Point>? {
        Log.d("DebugBoard", "detectCorners: mat.empty() = ${croppedMat.empty()}, size = ${croppedMat.size()}")
        Log.d("DebugBoard", "detectCorners: 開始。入力サイズ: ${croppedMat.cols()}x${croppedMat.rows()}")
        if (croppedMat.empty()) {
            Log.e("DebugBoard", "detectCorners: 画像が空です")
            return null
        }
        Log.d("DebugBoard", "detectCorners: チャンネル数 = ${croppedMat.channels()}")
        // もしここで 3 や 4 が返ってくるなら、カラー画像がそのまま渡されています

        val hRes = gridDetector.detectGridLines(croppedMat, GridLineDetector.Axis.HORIZONTAL)
        val vRes = gridDetector.detectGridLines(croppedMat, GridLineDetector.Axis.VERTICAL)

        if (hRes == null || vRes == null) {
            Log.e("DebugBoard", "detectCorners: 線検出結果がnullです")
            return null
        }

        Log.d("DebugBoard", "H-Confidence: ${hRes.confidence}, V-Confidence: ${vRes.confidence}")

        val logicalRect = BoardEdgeFromGridLines.compute(hRes, vRes)
        Log.d("DebugBoard", "計算された論理矩形: $logicalRect")

        val corners = listOf(
            Point(logicalRect.leftEdge, logicalRect.topEdge),
            Point(logicalRect.rightEdge, logicalRect.topEdge),
            Point(logicalRect.rightEdge, logicalRect.bottomEdge),
            Point(logicalRect.leftEdge, logicalRect.bottomEdge)
        )
        Log.d("DebugBoard", "四隅の座標生成完了: $corners")

        // 4. デバッグ画像の保存
        Log.d("DebugBoard", "デバッグ画像生成開始")
        try {
            val debugMat = drawDebugOverlay(croppedMat, hRes, vRes, corners)
            saveDebugImage(context, debugMat)
            Log.d("DebugBoard", "デバッグ画像処理終了")
        } catch (e: Exception) {
            Log.e("DebugBoard", "デバッグ画像処理中にエラー: ${e.message}")
        }

        return corners
    }

    // デバッグ画像の生成（罫線19本を描画）
    private fun drawDebugOverlay(srcMat: Mat, hRes: GridLineDetector.GridFitResult, vRes: GridLineDetector.GridFitResult, corners: List<Point>?): Mat {
        val debug = srcMat.clone()
        if (debug.channels() == 1) Imgproc.cvtColor(debug, debug, Imgproc.COLOR_GRAY2BGR)

        // 19本の水平線を描画（緑）
        hRes.positions.forEach { y ->
            Imgproc.line(debug, Point(0.0, y), Point(debug.cols().toDouble(), y), Scalar(0.0, 255.0, 0.0), 1)
        }
        // 19本の垂直線を描画（緑）
        vRes.positions.forEach { x ->
            Imgproc.line(debug, Point(x, 0.0), Point(x, debug.rows().toDouble()), Scalar(0.0, 255.0, 0.0), 1)
        }

        // 算出された境界線（青）
        corners?.let {
            Imgproc.rectangle(debug, it[0], it[2], Scalar(255.0, 0.0, 0.0), 3)
        }

        return debug
    }

    // saveDebugImage は既存のものをそのまま使用可能です
    private fun saveDebugImage(context: Context, mat: Mat) { /* ... 既存の実装 ... */ }

    // GuidedBoardDetector.kt に追加
    fun warpBoard(src: Mat, corners: List<Point>): Mat {
        val dstSize = org.opencv.core.Size(1000.0, 1000.0)

        val srcCorners = org.opencv.core.MatOfPoint2f(corners[0], corners[1], corners[2], corners[3])
        val dstCorners = org.opencv.core.MatOfPoint2f(
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
