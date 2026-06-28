package com.github.tkirino.gobanreader

import androidx.compose.ui.unit.IntSize
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import org.opencv.imgcodecs.Imgcodecs

// --- data class GuideFrame はそのまま保持 ---
data class GuideFrame(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
)

// --- ここから下を入れ替えます ---
class GuidedBoardDetector(
    private val previewSize: IntSize,
    private val guideSizePx: Float
) {
    // 外部からファイルを渡し、処理後のMat（画像データ）を返す窓口
    fun process(photoFile: File): Mat? {
        val srcMat = Imgcodecs.imread(photoFile.absolutePath)
        if (srcMat.empty()) return null

        // ガイド枠を計算する（CameraScreenから移動）
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

        // 四隅の検出を呼び出す
        val corners = detectCorners(srcMat, guide) ?: return null

        // 射影変換を実行する
        return warpBoard(srcMat, corners)
    }

    private fun detectCorners(src: Mat, guide: GuideFrame): List<Point>? {
        // 1. 画像処理パイプライン（デバッグ成功済みの処理）
        val resized = Mat()
        val scale = 0.25
        Imgproc.resize(src, resized, Size(src.cols() * scale, src.rows() * scale))

        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_BGR2GRAY)

        val edges = Mat()
        Imgproc.Canny(gray, edges, 20.0, 80.0)

        // 2. 直線検出
        val lines = Mat()
        Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180, 30, 50.0, 20.0)

        val horizontalLines = mutableListOf<DoubleArray>()
        val verticalLines = mutableListOf<DoubleArray>()

        // 3. 線の分類
        for (i in 0 until lines.rows()) {
            val l = lines.get(i, 0)
            val dx = l[2] - l[0]
            val dy = l[3] - l[1]
            val angle = Math.atan2(dy, dx) * 180 / Math.PI

            if (Math.abs(angle) < 15) {
                horizontalLines.add(l)
            } else if (Math.abs(angle - 90) < 15 || Math.abs(angle + 90) < 15) {
                verticalLines.add(l)
            }
        }

        // 4. ガイド枠周辺の線のみを抽出する絞り込みロジック
        val margin = 500.0 // 余裕を持たせる
        val invScale = 1.0 / scale
        val topEdges = horizontalLines.filter {
            it[1] > (guide.top * scale) - margin && it[1] < (guide.top * scale) + margin
        }
        val bottomEdges = horizontalLines.filter {
            it[1] > (guide.bottom * scale) - margin && it[1] < (guide.bottom * scale) + margin
        }
        val leftEdges = verticalLines.filter {
            it[0] > (guide.left * scale) - margin && it[0] < (guide.left * scale) + margin
        }
        val rightEdges = verticalLines.filter {
            it[0] > (guide.right * scale) - margin && it[0] < (guide.right * scale) + margin
        }

        // 平均を計算（空の場合はガイド枠の値を採用してNaNを回避）
        val topEdgeY = if (topEdges.isNotEmpty()) {
            topEdges.map { it[1] }.average() * invScale
        } else {
            horizontalLines.minByOrNull { it[1] }?.get(1)?.times(invScale) ?: guide.top
        }
        val bottomEdgeY = if (bottomEdges.isNotEmpty()) bottomEdges.map { it[1] }.average() * invScale else guide.bottom
        val leftEdgeX = if (leftEdges.isNotEmpty()) leftEdges.map { it[0] }.average() * invScale else guide.left
        val rightEdgeX = if (rightEdges.isNotEmpty()) rightEdges.map { it[0] }.average() * invScale else guide.right

        // 5. 異常値チェック（もし計算できなかったら安全のためガイド枠を返す）
        if (topEdgeY.isNaN() || bottomEdgeY.isNaN() || leftEdgeX.isNaN() || rightEdgeX.isNaN()) {
            return null
        }

        // 6. 最終的な4隅の確定
        return listOf(
            Point(leftEdgeX, topEdgeY),    // 左上
            Point(rightEdgeX, topEdgeY),   // 右上
            Point(rightEdgeX, bottomEdgeY),// 右下
            Point(leftEdgeX, bottomEdgeY)  // 左下
        )
    }

    private fun warpBoard(src: Mat, corners: List<Point>): Mat {
        // ソートと変換ロジックをここに集約
        val sorted = sortCorners(corners)
        val srcCorners = MatOfPoint2f(*sorted.toTypedArray())
        val dstSize = Size(1000.0, 1073.0)
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

    // 補助関数をこのクラス内に移動
    private fun sortCorners(points: List<Point>): List<Point> {
        val topLeft = points.minByOrNull { it.x + it.y }!!
        val bottomRight = points.maxByOrNull { it.x + it.y }!!
        val topRight = points.maxByOrNull { it.x - it.y }!!
        val bottomLeft = points.minByOrNull { it.x - it.y }!!
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }
}
