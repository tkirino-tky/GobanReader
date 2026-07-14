package com.github.tkirino.gobanreader.vision

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

class CornerLineDetector {

    // 角度制限をクラス定数として管理（ご指摘のあった厳格な制限）
    private val horizontalAngleToleranceDeg = 5.0
    private val verticalAngleToleranceDeg = 5.0

    // CLAHEのパラメータ
    private val claheClipLimit = 2.0

    /**
     * 各ROIから線分候補を抽出するメインメソッド
     */
    fun detectCandidates(src: Mat, roiList: List<Rect>, edgeTypes: List<EdgeType>): List<EdgeCandidates> {
        require(roiList.size == edgeTypes.size) { "ROIとEdgeTypeの数は一致させる必要があります" }

        return roiList.mapIndexed { index, roi ->
            extractCandidatesFromRoi(src, roi, edgeTypes[index])
        }
    }

    private fun extractCandidatesFromRoi(src: Mat, roi: Rect, type: EdgeType): EdgeCandidates {
        val matRoi = Mat(src, roi)

        // 前処理
        val gray = Mat()
        Imgproc.cvtColor(matRoi, gray, Imgproc.COLOR_BGR2GRAY)
        val enhanced = Mat()
        Imgproc.createCLAHE(claheClipLimit, Size(8.0, 8.0)).apply(gray, enhanced)

        // LSD検出
        val lsd = Imgproc.createLineSegmentDetector(
            Imgproc.LSD_REFINE_STD, 1.0, 0.6, 2.0, 22.5, 0.0, 0.6, 1024
        )
        val lines = Mat()
        lsd.detect(enhanced, lines)

        val candidates = mutableListOf<LineCandidate>()
        val isHorizontal = (type == EdgeType.TOP || type == EdgeType.BOTTOM)
        val tolerance = if (isHorizontal) horizontalAngleToleranceDeg else verticalAngleToleranceDeg
        val expectedAngle = if (isHorizontal) 0.0 else 90.0

        // 碁盤の辺とみなす最小の長さを設定（例として200を指定）
        val minLength = 100.0

        for (i in 0 until lines.rows()) {
            val data = lines.get(i, 0)
            val p1 = Point(data[0] + roi.x, data[1] + roi.y)
            val p2 = Point(data[2] + roi.x, data[3] + roi.y)

            // 【追加・修正箇所】長さフィルタリングを先に行う
            val length = hypot(p2.x - p1.x, p2.y - p1.y)
            if (length < minLength) {
                continue // 短い線はここで無視する
            }

            val angle = Math.toDegrees(atan2(p2.y - p1.y, p2.x - p1.x)).let {
                if (it < 0) it + 180 else it
            }

            // 角度フィルタリング
            if (angleDiff(angle, expectedAngle) > tolerance) {
                continue
            }

            // スコア計算
            val midX = (p1.x + p2.x) / 2.0
            val midY = (p1.y + p2.y) / 2.0

            val distToBoundary = when (type) {
                EdgeType.TOP -> abs(midY - roi.y)
                EdgeType.BOTTOM -> abs((roi.y + roi.height) - midY)
                EdgeType.LEFT -> abs(midX - roi.x)
                EdgeType.RIGHT -> abs((roi.x + roi.width) - midX)
            }

            val score = length - (distToBoundary * 2.0)
            candidates.add(LineCandidate(calculateLineEquation(p1, p2), score, p1, p2))
        }

        matRoi.release()
        gray.release()
        enhanced.release()
        lines.release()

        return EdgeCandidates(type, candidates.sortedByDescending { it.score })
    }

    private fun calculateLineEquation(p1: Point, p2: Point): LineEquation {
        val a = p2.y - p1.y
        val b = p1.x - p2.x
        val c = a * p1.x + b * p1.y
        return LineEquation(a, b, c)
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val diff = abs(a - b) % 180.0
        return min(diff, 180.0 - diff)
    }
}
