package com.github.tkirino.gobanreader.vision

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

// 辺の定義と結果を保持するクラス
enum class EdgeType { TOP, BOTTOM, LEFT, RIGHT }
// CornerLineDetector.kt 内の定義を修正
data class CornerResult(
    val roi: Rect,
    val detectedLine: Line?,
    val allCandidates: List<Line> = emptyList()
)

class CornerLineDetector {

    // 設定値をクラス内部の定数として定義し、外部クラスへの依存をなくしました
    private val houghThreshold = 50
    private val houghMinLineLength = 50.0
    private val houghMaxLineGap = 10.0
    private val horizontalAngleToleranceDeg = 5.0
    private val verticalAngleToleranceDeg = 5.0
    private val clusterOffsetTolerancePx = 20.0
    private val segmentCountBonus = 10.0
    private val boundaryDistanceWeight = 2.0 // 0.5 から 2.0 に変更
    private val claheClipLimit = 2.0
    private val cannyLowerRatio = 0.5
    private val cannyUpperRatio = 1.5

    fun detectCornerLines(src: Mat, roiList: List<Rect>, edgeTypes: List<EdgeType>): List<CornerResult> {
        require(roiList.size == edgeTypes.size) { "ROIとEdgeTypeの数は一致させる必要があります" }

        return roiList.mapIndexed { index, roi ->
            val (bestLine, candidates) = extractBestLineFromRoi(src, roi, edgeTypes[index])
            CornerResult(roi, bestLine, candidates)
        }
    }

    private fun extractBestLineFromRoi(src: Mat, roi: Rect, type: EdgeType): Pair<Line?, List<Line>> {
        val matRoi = Mat(src, roi)
        val edges = preprocessAndDetectEdges(matRoi)
        val linesMat = Mat()

        Imgproc.HoughLinesP(edges, linesMat, 1.0, PI / 180,
            houghThreshold, houghMinLineLength, houghMaxLineGap)

        val rawCandidates = mutableListOf<Line>()
        for (i in 0 until linesMat.rows()) {
            val data = linesMat.get(i, 0)
            rawCandidates.add(Line(data[0] + roi.x, data[1] + roi.y, data[2] + roi.x, data[3] + roi.y))
        }

        val isHorizontal = (type == EdgeType.TOP || type == EdgeType.BOTTOM)
        val tolerance = if (isHorizontal) horizontalAngleToleranceDeg else verticalAngleToleranceDeg
        val expectedAngle = if (isHorizontal) 0.0 else 90.0

        val angleFiltered = rawCandidates.filter { angleDiff(it.angleDeg, expectedAngle) <= tolerance }
        if (angleFiltered.isEmpty()) return Pair(null, emptyList())

        val clusters = mergeCollinearSegments(angleFiltered, isHorizontal)

        // CornerLineDetector.kt の extractBestLineFromRoi 内
        // 既存のコードの maxByOrNull の中身を以下のように強化します
        // CornerLineDetector.kt の extractBestLineFromRoi 内のスコア計算部分
        val bestLine = clusters.maxByOrNull { cluster ->
            val line = cluster.mergedLine
            val mid = if (isHorizontal) (line.y1 + line.y2) / 2.0 else (line.x1 + line.x2) / 2.0

            // 既存の距離ペナルティ
            val distToBoundary = when (type) {
                EdgeType.TOP -> mid - roi.y
                EdgeType.BOTTOM -> (roi.y + roi.height) - mid
                EdgeType.LEFT -> mid - roi.x
                EdgeType.RIGHT -> (roi.x + roi.width) - mid
            }

            // ★追加：角（Corner）への到達度を評価する
            // 線がROIの端にどれだけ近いか（= 角に近いか）を計算
            val distToCorner = if (isHorizontal) {
                min(abs(line.x1 - roi.x), abs(line.x2 - (roi.x + roi.width)))
            } else {
                min(abs(line.y1 - roi.y), abs(line.y2 - (roi.y + roi.height)))
            }

            val score = (line.length * 5.0) + (cluster.segmentCount * segmentCountBonus) - (distToBoundary * 2.0)

            // 「角に近づくほど高スコア」とする補正（1.0 の係数は適宜調整可能です）
            score - (distToCorner * 1.0)
        }?.mergedLine

        return Pair(bestLine, angleFiltered)
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val diff = abs(a - b) % 180.0
        return min(diff, 180.0 - diff)
    }

    private fun mergeCollinearSegments(lines: List<Line>, isHorizontal: Boolean): List<LineCluster> {
        val offset = { l: Line -> if (isHorizontal) (l.y1 + l.y2) / 2.0 else (l.x1 + l.x2) / 2.0 }
        val sorted = lines.sortedBy(offset)
        val clusters = mutableListOf<MutableList<Line>>()
        for (line in sorted) {
            val last = clusters.lastOrNull()?.last()
            if (last != null && abs(offset(line) - offset(last)) <= clusterOffsetTolerancePx) {
                clusters.last().add(line)
            } else {
                clusters.add(mutableListOf(line))
            }
        }
        return clusters.map { group ->
            val pts = group.flatMap { listOf(it.x1 to it.y1, it.x2 to it.y2) }
            val merged = if (isHorizontal) {
                Line(pts.minByOrNull { it.first }!!.first, pts.minByOrNull { it.first }!!.second,
                    pts.maxByOrNull { it.first }!!.first, pts.maxByOrNull { it.first }!!.second)
            } else {
                Line(pts.minByOrNull { it.second }!!.first, pts.minByOrNull { it.second }!!.second,
                    pts.maxByOrNull { it.second }!!.first, pts.maxByOrNull { it.second }!!.second)
            }
            LineCluster(merged, group.size)
        }
    }

    private fun preprocessAndDetectEdges(roi: Mat): Mat {
        val gray = Mat(); Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY)
        val enhanced = Mat(); Imgproc.createCLAHE(claheClipLimit, Size(8.0, 8.0)).apply(gray, enhanced)
        val blurred = Mat(); Imgproc.GaussianBlur(enhanced, blurred, Size(5.0, 5.0), 0.0)
        val mean = Core.mean(blurred).`val`[0]
        val edges = Mat()
        Imgproc.Canny(blurred, edges, (mean * cannyLowerRatio).coerceAtLeast(10.0),
            (mean * cannyUpperRatio).coerceAtMost(255.0))
        return edges
    }

    private data class LineCluster(val mergedLine: Line, val segmentCount: Int)
}
