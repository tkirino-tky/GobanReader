package com.github.tkirino.gobanreader.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.github.tkirino.gobanreader.vision.GridLineDetector
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class GuidedBoardDetector(
    private val context: Context,
    private val guideRect: Rect
) {
    private val gridDetector = GridLineDetector()

    fun detectCorners(croppedMat: Mat): List<Point>? {
        Log.d("DebugBoard", "detectCorners: 開始。入力サイズ: ${croppedMat.cols()}x${croppedMat.rows()}")
        if (croppedMat.empty()) return null

        val hRes = gridDetector.detectGridLines(croppedMat, GridLineDetector.Axis.HORIZONTAL)
        val vRes = gridDetector.detectGridLines(croppedMat, GridLineDetector.Axis.VERTICAL)

        if (hRes == null || vRes == null) {
            Log.e("DebugBoard", "detectCorners: 線検出結果がnullです")
            return null
        }

        val logicalRect = BoardEdgeFromGridLines.compute(hRes, vRes)
        val corners = listOf(
            Point(logicalRect.leftEdge, logicalRect.topEdge),
            Point(logicalRect.rightEdge, logicalRect.topEdge),
            Point(logicalRect.rightEdge, logicalRect.bottomEdge),
            Point(logicalRect.leftEdge, logicalRect.bottomEdge)
        )

        // デバッグ画像保存の判定
        val minConfidence = minOf(hRes.confidence, vRes.confidence)
        Log.d("DebugBoard", "minConfidence = $minConfidence")

        try {
            val debugMat = drawDebugOverlay(croppedMat, hRes, vRes, corners)
            saveDebugImage(context, debugMat)
            Log.d("DebugBoard", "デバッグ画像処理終了")
        } catch (e: Exception) {
            Log.e("DebugBoard", "デバッグ画像処理中にエラー: ${e.message}")
        }

        return corners
    }

    private fun drawDebugOverlay(srcMat: Mat, hRes: GridLineDetector.GridFitResult, vRes: GridLineDetector.GridFitResult, corners: List<Point>?): Mat {
        val debug = srcMat.clone()
        if (debug.channels() == 1) Imgproc.cvtColor(debug, debug, Imgproc.COLOR_GRAY2BGR)

        // 19本の水平線を描画（マゼンタに変更）
        hRes.positions.forEach { y ->
            // OpenCvのScalarはBGR順です。マゼンタ = (255, 0, 255)
            Imgproc.line(debug, Point(0.0, y), Point(debug.cols().toDouble(), y), Scalar(255.0, 0.0, 255.0), 1)
        }
        // 19本の垂直線を描画（マゼンタに変更）
        vRes.positions.forEach { x ->
            // OpenCvのScalarはBGR順です。マゼンタ = (255, 0, 255)
            Imgproc.line(debug, Point(x, 0.0), Point(x, debug.rows().toDouble()), Scalar(255.0, 0.0, 255.0), 1)
        }
        corners?.let { Imgproc.rectangle(debug, it[0], it[2], Scalar(255.0, 0.0, 0.0), 3) }
        return debug
    }

    private fun saveDebugImage(context: Context, mat: Mat) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "debug_$timeStamp.png"
        val file = File(context.getExternalFilesDir(null), fileName)

        Log.d("DebugBoard", "saveDebugImage: 保存開始 -> ${file.absolutePath}")

        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)

        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        Log.d("DebugBoard", "saveDebugImage: 保存完了")
    }

    fun warpBoard(src: Mat, corners: List<Point>): Mat {
        val dstSize = Size(1000.0, 1000.0)
        val srcCorners = MatOfPoint2f(corners[0], corners[1], corners[2], corners[3])
        val dstCorners = MatOfPoint2f(Point(0.0, 0.0), Point(1000.0, 0.0), Point(1000.0, 1000.0), Point(0.0, 1000.0))
        val transform = Imgproc.getPerspectiveTransform(srcCorners, dstCorners)
        val warped = Mat(1000, 1000, src.type())
        Imgproc.warpPerspective(src, warped, transform, dstSize)
        return warped
    }
}
