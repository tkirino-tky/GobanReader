package com.github.tkirino.gobanreader.vision

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 碁盤の物理的な外形（四隅）を輪郭検出から推定する。
 *
 * 【設計意図】
 * ・GridLineDetectorが前提とする「粗く矩形補正済み」を作るための、
 *   最初の粗いホモグラフィ用の四隅を得ることが目的。
 * ・ここでの精度は追求しすぎない。多少ズレていても、後段で
 *   人間がハンドルをドラッグして修正する前提（CornerAdjustScreen）。
 * ・そのため、このクラスは「検出できたか/できなかったか」と
 *   「見つかった4点（画像のオリジナル解像度の座標系）」を返すだけの
 *   単純な責務にとどめる。
 */
class BoardCornerDetector {

    data class DetectionResult(
        val corners: List<Point>,   // 左上・右上・右下・左下の順（画像座標系）
        val found: Boolean,
        val debugReason: String     // 見つからなかった場合の簡単な理由（ログ・デバッグ用）
    )

    /**
     * @param grayOrColor グレースケールでもカラーでも可（内部でグレースケール化する）
     */
    fun detect(input: Mat): DetectionResult {
        val gray = Mat()
        if (input.channels() == 1) {
            input.copyTo(gray)
        } else {
            Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)
        }

        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        val edged = Mat()
        Imgproc.Canny(blurred, edged, 50.0, 150.0)
        Imgproc.dilate(edged, edged, Mat(), Point(-1.0, -1.0), 2)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            edged, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        hierarchy.release()
        blurred.release()
        edged.release()
        gray.release()

        if (contours.isEmpty()) {
            return DetectionResult(emptyList(), false, "輪郭が1つも見つからなかった")
        }

        val imageArea = input.rows().toDouble() * input.cols().toDouble()

        var best: List<Point>? = null
        var bestArea = 0.0
        var rejectReason = "4頂点に近似できる輪郭がなかった"

        for (c in contours) {
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            c2f.release()

            if (approx.total() != 4L) {
                approx.release()
                continue
            }

            val pts = approx.toArray().toList()
            approx.release()

            val area = polygonArea(pts)
            val areaRatio = area / imageArea

            // ① 面積が画像全体に対してあまりに小さい/大きい場合は棄却
            if (areaRatio < 0.15 || areaRatio > 0.95) {
                rejectReason = "面積比が不自然（$areaRatio）"
                continue
            }

            // ② 四隅の内角チェック（60°〜120°程度を許容。撮影角度の煽りを考慮）
            val ordered = orderCorners(pts)
            if (!hasReasonableAngles(ordered)) {
                rejectReason = "内角が矩形らしくない"
                continue
            }

            // ③ アスペクト比チェック（碁盤は縦がわずかに長い約1.04）
            val aspect = estimateAspect(ordered)
            if (aspect < 0.7 || aspect > 1.5) {
                rejectReason = "アスペクト比が碁盤らしくない（$aspect）"
                continue
            }

            if (area > bestArea) {
                bestArea = area
                best = ordered
            }
        }

        return if (best != null) {
            DetectionResult(best, true, "OK")
        } else {
            DetectionResult(emptyList(), false, rejectReason)
        }
    }

    // 4点を左上・右上・右下・左下の順に並べ替える
    private fun orderCorners(pts: List<Point>): List<Point> {
        // sum(x+y)最小=左上, 最大=右下。diff(x-y)最小=左下, 最大=右上
        val topLeft = pts.minByOrNull { it.x + it.y }!!
        val bottomRight = pts.maxByOrNull { it.x + it.y }!!
        val topRight = pts.maxByOrNull { it.x - it.y }!!
        val bottomLeft = pts.minByOrNull { it.x - it.y }!!

        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun hasReasonableAngles(corners: List<Point>): Boolean {
        for (i in corners.indices) {
            val prev = corners[(i + corners.size - 1) % corners.size]
            val cur = corners[i]
            val next = corners[(i + 1) % corners.size]

            val v1x = prev.x - cur.x
            val v1y = prev.y - cur.y
            val v2x = next.x - cur.x
            val v2y = next.y - cur.y

            val dot = v1x * v2x + v1y * v2y
            val mag1 = hypot(v1x, v1y)
            val mag2 = hypot(v2x, v2y)
            if (mag1 < 1e-6 || mag2 < 1e-6) return false

            val cosAngle = (dot / (mag1 * mag2)).coerceIn(-1.0, 1.0)
            val angleDeg = Math.toDegrees(kotlin.math.acos(cosAngle))

            if (angleDeg < 60.0 || angleDeg > 120.0) return false
        }
        return true
    }

    private fun estimateAspect(corners: List<Point>): Double {
        val (tl, tr, br, bl) = corners
        val topWidth = hypot(tr.x - tl.x, tr.y - tl.y)
        val bottomWidth = hypot(br.x - bl.x, br.y - bl.y)
        val leftHeight = hypot(bl.x - tl.x, bl.y - tl.y)
        val rightHeight = hypot(br.x - tr.x, br.y - tr.y)

        val avgWidth = (topWidth + bottomWidth) / 2.0
        val avgHeight = (leftHeight + rightHeight) / 2.0
        if (avgWidth < 1e-6) return 0.0
        return avgHeight / avgWidth
    }

    private fun polygonArea(pts: List<Point>): Double {
        var area = 0.0
        for (i in pts.indices) {
            val p1 = pts[i]
            val p2 = pts[(i + 1) % pts.size]
            area += p1.x * p2.y - p2.x * p1.y
        }
        return abs(area) / 2.0
    }
}

// destructuring用（List<Point>の4要素を tl, tr, br, bl として受け取れるように）
private operator fun List<Point>.component1() = this[0]
private operator fun List<Point>.component2() = this[1]
private operator fun List<Point>.component3() = this[2]
private operator fun List<Point>.component4() = this[3]
