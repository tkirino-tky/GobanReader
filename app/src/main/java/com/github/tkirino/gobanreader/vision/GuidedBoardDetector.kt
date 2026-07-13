package com.github.tkirino.gobanreader.vision

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class GuidedBoardDetector(
    private val context: Context,
    private val guideRect: Rect
) {
    private val detector = CornerLineDetector()

    fun detectCorners(croppedMat: Mat): List<Point>? {
        Log.d("GuidedBoardDetector", "detectCorners 開始: cols=${croppedMat.cols()}, rows=${croppedMat.rows()}")

        if (croppedMat.empty()) {
            Log.e("GuidedBoardDetector", "画像が空です")
            return null
        }

        // GuidedBoardDetector.kt 内の rois 定義部分を修正案
        val bandWidth = (croppedMat.cols() / 10).coerceIn(40, 100)

        val rois = listOf(
            Rect(0, 0, croppedMat.cols(), bandWidth),
            Rect(0, croppedMat.rows() - bandWidth, croppedMat.cols(), bandWidth),
            Rect(0, 0, bandWidth, croppedMat.rows()),
            // 右側のROIの x を調整して外側に寄せる
            Rect(croppedMat.cols() - bandWidth - 10, 0, bandWidth + 10, croppedMat.rows())
        )

        val edgeTypes = listOf(
            EdgeType.TOP,
            EdgeType.BOTTOM,
            EdgeType.LEFT,
            EdgeType.RIGHT
        )

        val results = detector.detectCornerLines(croppedMat, rois, edgeTypes)

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

        val resultsCorners = listOf(topLeft, topRight, bottomRight, bottomLeft)

        // デバッグ画像の生成と保存
        val debugMat = drawDebugOverlay(croppedMat, rois, results, resultsCorners)
        saveDebugImage(this.context, debugMat)

        Log.d("GuidedBoardDetector", "四隅の座標導出に成功: TL=$topLeft, TR=$topRight, BR=$bottomRight, BL=$bottomLeft")

        return resultsCorners
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

    fun warpBoard(src: Mat, corners: List<Point>): Mat {
        val dstSize = Size(1000.0, 1000.0)

        val srcCorners = MatOfPoint2f(corners[0], corners[1], corners[2], corners[3])
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

// デバッグ画像生成関数
// GuidedBoardDetector.kt の drawDebugOverlay 関数
fun drawDebugOverlay(
    srcMat: Mat,
    rois: List<Rect>,
    results: List<CornerResult>, // ここに全候補が入っている前提
    corners: List<Point>?
): Mat {
    val debug = srcMat.clone()
    if (debug.channels() == 1) Imgproc.cvtColor(debug, debug, Imgproc.COLOR_GRAY2BGR)

    // 1. 探索ROI(黄)
    rois.forEach { r ->
        Imgproc.rectangle(debug, r.tl(), r.br(), Scalar(0.0, 255.0, 255.0), 2)
    }

    // 2. ★追加：すべての候補線を薄い色（グレー）で描画
    results.forEach { res ->
        res.allCandidates?.forEach { line -> // allCandidates を持つ必要があります
            Imgproc.line(debug, Point(line.x1, line.y1), Point(line.x2, line.y2), Scalar(128.0, 128.0, 128.0), 1)
        }
    }

    // 3. 最終選定ライン(赤・太く)
    results.forEach { res ->
        res.detectedLine?.let { line ->
            Imgproc.line(debug, Point(line.x1, line.y1), Point(line.x2, line.y2), Scalar(0.0, 0.0, 255.0), 3)
        }
    }

    // 4. 交点(マゼンタ)
    corners?.forEach { c ->
        Imgproc.circle(debug, c, 15, Scalar(255.0, 0.0, 255.0), -1)
    }

    return debug
}

// Downloadフォルダ保存関数
fun saveDebugImage(context: Context, mat: Mat) {
    val filename = "debug_${System.currentTimeMillis()}.png"

    // MatからBitmapへの変換を確実にする
    val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(mat, bitmap)

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
    }

    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)

    if (uri != null) {
        try {
            resolver.openOutputStream(uri)?.use { out ->
                // 圧縮率を明示し、フラッシュとクローズを確実に行う
                val success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
                Log.d("GuidedBoardDetector", "画像保存成功: $success, uri: $uri")
            }
        } catch (e: Exception) {
            Log.e("GuidedBoardDetector", "画像保存中にエラーが発生しました: ${e.message}")
        } finally {
            // Bitmapのメモリ解放（処理が終了しているので安全）
            bitmap.recycle()
        }
    } else {
        Log.e("GuidedBoardDetector", "URIの作成に失敗しました")
    }
}
