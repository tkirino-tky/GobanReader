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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
class GuidedBoardDetector(
    private val context: Context,
    private val guideRect: Rect
) {
    private val detector = CornerLineDetector()

    // GuidedBoardDetector.kt 内の detectCorners 関数
    fun detectCorners(croppedMat: Mat): List<Point>? {
        if (croppedMat.empty()) return null

        val bandWidth = (croppedMat.cols() / 10).coerceIn(40, 100)
        val rois: List<Rect> = listOf(
            Rect(0, 0, croppedMat.cols(), bandWidth),
            Rect(0, croppedMat.rows() - bandWidth, croppedMat.cols(), bandWidth),
            Rect(0, 0, bandWidth, croppedMat.rows()),
            Rect(croppedMat.cols() - bandWidth - 10, 0, bandWidth + 10, croppedMat.rows())
        )
        val edgeTypes = listOf(EdgeType.TOP, EdgeType.BOTTOM, EdgeType.LEFT, EdgeType.RIGHT)
        val allEdgeCandidates = detector.detectCandidates(croppedMat, rois, edgeTypes)

        allEdgeCandidates.forEachIndexed { index, edge ->
            Log.d("LineCandidate", "EdgeType: ${edge.type}, Count: ${edge.candidates.size}")
            edge.candidates.forEachIndexed { cIndex, cand ->
                Log.d("LineCandidate", "  [$cIndex] p1:${cand.p1}, p2:${cand.p2}, Len:${hypot(cand.p2.x-cand.p1.x, cand.p2.y-cand.p1.y).toInt()}, Score:${"%.2f".format(cand.score)}")
            }
        }
        // 1. 各辺から「候補上位3本」を取り出す
        val bestLinesList = allEdgeCandidates.map { edge -> edge.candidates.take(3) }



        // 2. 総当たりでベストな組み合わせを探す
        var bestScore = -Double.MAX_VALUE
        var bestCombination: List<LineCandidate>? = null

        for (top in bestLinesList[0]) {
            for (bottom in bestLinesList[1]) {
                for (left in bestLinesList[2]) {
                    for (right in bestLinesList[3]) {
                        val score = evaluateCombination(top, bottom, left, right)
                        if (score > bestScore) {
                            bestScore = score
                            bestCombination = listOf(top, bottom, left, right)
                        }
                    }
                }
            }
        }

        // 3. 採用した組み合わせで交点計算
        val combo = bestCombination ?: return null
        val lineTop = Line(combo[0].p1.x, combo[0].p1.y, combo[0].p2.x, combo[0].p2.y)
        val lineBottom = Line(combo[1].p1.x, combo[1].p1.y, combo[1].p2.x, combo[1].p2.y)
        val lineLeft = Line(combo[2].p1.x, combo[2].p1.y, combo[2].p2.x, combo[2].p2.y)
        val lineRight = Line(combo[3].p1.x, combo[3].p1.y, combo[3].p2.x, combo[3].p2.y)

        val topLeft = computeIntersection(lineTop, lineLeft)
        val topRight = computeIntersection(lineTop, lineRight)
        val bottomRight = computeIntersection(lineBottom, lineRight)
        val bottomLeft = computeIntersection(lineBottom, lineLeft)

        if (topLeft == null || topRight == null || bottomRight == null || bottomLeft == null) {
            return null
        }

        val resultsCorners = listOf(topLeft, topRight, bottomRight, bottomLeft)

        // 4. デバッグ画像の生成と保存
        val debugMat = drawDebugOverlay(croppedMat, rois, allEdgeCandidates, resultsCorners)
        saveDebugImage(this.context, debugMat)

        return resultsCorners
    }

    // 評価関数（ここをこれから作り込みます）
    private fun evaluateCombination(
        top: LineCandidate,
        bottom: LineCandidate,
        left: LineCandidate,
        right: LineCandidate
    ): Double {
        // 角度の計算用ヘルパー（関数内での定義を忘れずに）
        fun getAngle(c: LineCandidate): Double {
            val angle = Math.toDegrees(atan2(c.p2.y - c.p1.y, c.p2.x - c.p1.x))
            return if (angle < 0) angle + 180 else angle
        }
        fun getLen(c: LineCandidate): Double = hypot(c.p2.x - c.p1.x, c.p2.y - c.p1.y)

        // 1. 角度ペナルティ：ズレが大きいほどスコアを下げる（係数5.0で重み付け）
        val angleScore = -(abs(getAngle(top) - 0.0) +
                abs(getAngle(bottom) - 0.0) +
                abs(getAngle(left) - 90.0) +
                abs(getAngle(right) - 90.0)) * 5.0

        // 2. 長さスコア：2乗することで「長い線」を圧倒的に優遇する
        // 短い線（ノイズ）は極端にスコアが低くなるため、自然と無視されます
        fun scoreLen(c: LineCandidate): Double = (getLen(c) * getLen(c)) * 0.01

        // 3. 総合スコア
        val totalScore = scoreLen(top) + scoreLen(bottom) + scoreLen(left) + scoreLen(right) + angleScore

        return totalScore
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
    results: List<EdgeCandidates>, // ここを変更
    corners: List<Point>?
): Mat {
    val debug = srcMat.clone()
    if (debug.channels() == 1) Imgproc.cvtColor(debug, debug, Imgproc.COLOR_GRAY2BGR)

    // 1. 探索ROI(黄)
    rois.forEach { r ->
        Imgproc.rectangle(debug, r.tl(), r.br(), Scalar(0.0, 255.0, 255.0), 2)
    }

    // 2. ★すべての候補線を薄い色で描画
    results.forEach { edge ->
        edge.candidates.forEach { line ->
            Imgproc.line(debug, line.p1, line.p2, Scalar(128.0, 128.0, 128.0), 1)
        }
    }

    // 3. 最終選定ライン(赤・太く)
    // bestLines から取り出して描画します
    val bestLines = results.map { it.candidates.firstOrNull() }
    bestLines.forEach { line ->
        line?.let {
            Imgproc.line(debug, it.p1, it.p2, Scalar(0.0, 0.0, 255.0), 3)
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
