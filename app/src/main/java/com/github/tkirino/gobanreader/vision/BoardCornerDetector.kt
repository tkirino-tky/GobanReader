package com.github.tkirino.gobanreader.vision

import android.content.Context
import com.github.tkirino.gobanreader.utility.DebugUtility
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot

class BoardCornerDetector {
    data class DetectionResult(
        val corners: List<Point>,
        val found: Boolean,
        val debugReason: String
    )

    fun detect(context: Context, input: Mat): DetectionResult {
        val gray = Mat()
        if (input.channels() == 1) input.copyTo(gray) else Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)

        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        val edged = Mat()
        Imgproc.Canny(blurred, edged, 50.0, 150.0)
        Imgproc.dilate(edged, edged, Mat(), Point(-1.0, -1.0), 2)

        // デバッグ出力
        DebugUtility.saveDebugImage(context, edged, "corner_edged")

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edged, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        hierarchy.release(); blurred.release(); edged.release(); gray.release()

        if (contours.isEmpty()) return DetectionResult(emptyList(), false, "輪郭なし")

        var best: List<Point>? = null
        var bestArea = 0.0
        val imageArea = input.rows().toDouble() * input.cols().toDouble()

        for (c in contours) {
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)

            if (approx.total() == 4L) {
                val pts = approx.toArray().toList()
                val area = polygonArea(pts)
                if (area / imageArea in 0.15..0.95) {
                    val ordered = orderCorners(pts)
                    if (hasReasonableAngles(ordered) && estimateAspect(ordered) in 0.7..1.5) {
                        if (area > bestArea) { bestArea = area; best = ordered }
                    }
                }
            }
            c2f.release(); approx.release()
        }
        return if (best != null) DetectionResult(best, true, "OK") else DetectionResult(emptyList(), false, "条件適合なし")
    }

    private fun orderCorners(pts: List<Point>): List<Point> {
        val topLeft = pts.minByOrNull { it.x + it.y }!!
        val bottomRight = pts.maxByOrNull { it.x + it.y }!!
        val topRight = pts.maxByOrNull { it.x - it.y }!!
        val bottomLeft = pts.minByOrNull { it.x - it.y }!!
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun hasReasonableAngles(corners: List<Point>): Boolean {
        for (i in corners.indices) {
            val prev = corners[(i + 3) % 4]; val cur = corners[i]; val next = corners[(i + 1) % 4]
            val v1x = prev.x - cur.x; val v1y = prev.y - cur.y
            val v2x = next.x - cur.x; val v2y = next.y - cur.y
            val dot = v1x * v2x + v1y * v2y
            val mag = hypot(v1x, v1y) * hypot(v2x, v2y)
            if (mag < 1e-6) return false
            val angleDeg = Math.toDegrees(kotlin.math.acos((dot / mag).coerceIn(-1.0, 1.0)))
            if (angleDeg < 60.0 || angleDeg > 120.0) return false
        }
        return true
    }

    private fun estimateAspect(corners: List<Point>): Double {
        val (tl, tr, br, bl) = corners
        val w = (hypot(tr.x - tl.x, tr.y - tl.y) + hypot(br.x - bl.x, br.y - bl.y)) / 2.0
        val h = (hypot(bl.x - tl.x, bl.y - tl.y) + hypot(br.x - tr.x, br.y - tr.y)) / 2.0
        return if (w < 1e-6) 0.0 else h / w
    }

    private fun polygonArea(pts: List<Point>): Double {
        var a = 0.0
        for (i in pts.indices) { val p1 = pts[i]; val p2 = pts[(i + 1) % pts.size]; a += p1.x * p2.y - p2.x * p1.y }
        return abs(a) / 2.0
    }
}
