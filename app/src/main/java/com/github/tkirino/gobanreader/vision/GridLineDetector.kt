package com.github.tkirino.gobanreader.vision

import android.util.Log
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.core.CvType
import kotlin.math.abs

/**
 * 粗く矩形補正された碁盤画像から、19本の罫線（水平・垂直）の位置を
 * 周期性を手がかりに検出する。
 *
 * 【設計意図】
 * ・罫線1本を単独で「一番長い/一番濃い線」として検出するのではなく、
 *   「ほぼ等間隔に19本並んでいる」という強い構造的制約を使う。
 * ・石は交点付近だけを覆い、線全体を覆うことは稀なので、行/列ごとに
 *   線らしさを"合算"すれば、多少の石による遮蔽があっても
 *   ピークとして残りやすい。
 * ・これにより、これまでHoughLinesPの「単発の長い直線」評価が
 *   影・側面・背景ノイズに負けていた弱点を回避する。
 *
 * 前提：入力は既に粗く矩形補正（perspective warp）済みで、
 * 碁盤の罫線がおおよそ水平・垂直になっていること。
 */
class GridLineDetector {

    data class GridFitResult(
        val positions: DoubleArray,   // 19本それぞれの座標（昇順）
        val spacing: Double,          // 平均罫線間隔
        val confidence: Double        // 0.0-1.0。等間隔からのズレの少なさ
    )

    /**
     * @param rectified 粗補正済みグレースケール画像
     * @param axis HORIZONTAL=水平罫線（y座標群を返す）, VERTICAL=垂直罫線（x座標群を返す）
     * @param expectedLineCount 通常19（碁盤の罫線本数）
     */
    fun detectGridLines(
        rectified: Mat,
        axis: Axis,
        expectedLineCount: Int = 19
    ): GridFitResult? {

        // ① 罫線らしさのスコアを1次元に投影する
        //    水平罫線を探す場合：Sobelのy方向微分（垂直方向のエッジ＝水平線を強調）を
        //    各行について絶対値合算する
        val sobel = Mat()
        if (axis == Axis.HORIZONTAL) {
            Imgproc.Sobel(rectified, sobel, CvType.CV_32F, 0, 1, 3)
        } else {
            Imgproc.Sobel(rectified, sobel, CvType.CV_32F, 1, 0, 3)
        }
        Core_abs(sobel)

        val length = if (axis == Axis.HORIZONTAL) rectified.rows() else rectified.cols()
        val score = DoubleArray(length)
        for (i in 0 until length) {
            score[i] = if (axis == Axis.HORIZONTAL) {
                rowSum(sobel, i)
            } else {
                colSum(sobel, i)
            }
        }
        sobel.release()

        // ② 大まかな理論間隔（画像サイズ / 19）を初期値として、
        //    各期待位置の近傍でピークを探し、サブピクセル精度で補正する
        val roughSpacing = length.toDouble() / expectedLineCount
        val positions = DoubleArray(expectedLineCount)
        val searchRadius = (roughSpacing * 0.4).toInt().coerceAtLeast(3)

        for (lineIndex in 0 until expectedLineCount) {
            val expectedPos = (lineIndex + 0.5) * roughSpacing
            val center = expectedPos.toInt().coerceIn(0, length - 1)
            val from = (center - searchRadius).coerceAtLeast(0)
            val to = (center + searchRadius).coerceAtMost(length - 1)

            var bestIdx = from
            var bestVal = score[from]
            for (i in from..to) {
                if (score[i] > bestVal) {
                    bestVal = score[i]
                    bestIdx = i
                }
            }
            // 放物線フィッティングによるサブピクセル補正
            positions[lineIndex] = subpixelRefine(score, bestIdx)
        }

        // ③ 各線のピーク強度を記録（最外周が石で覆われているケースの検出に使う）
        val peakStrengths = DoubleArray(expectedLineCount) { i -> score[positions[i].toInt().coerceIn(0, length - 1)] }
        val interiorIndices = 1 until (expectedLineCount - 1)
        val interiorMeanStrength = interiorIndices.map { peakStrengths[it] }.average()

        // 【追加】最外周（0番目・最後）のピーク強度が内側平均より著しく低い場合、
        // その罫線自体は石で覆われて信頼できないとみなし、内側の等間隔性から
        // 回帰直線で外挿した位置に置き換える。
        // これは「盤面がほぼ全て石で埋まり、かつ1路目・19路目に沿って石が
        // 連続している」という最も厳しい局面（例：中盤の混戦）への対策。
        val weakThresholdRatio = 0.35  // 内側平均の35%未満なら「石に覆われている」とみなす

        val interiorPositions = interiorIndices.map { positions[it] }
        val interiorRanks = interiorIndices.map { it.toDouble() }
        val (slope, intercept) = linearRegression(interiorRanks, interiorPositions)

        val edgeIndices = listOf(0, expectedLineCount - 1)
        for (i in edgeIndices) {
            if (peakStrengths[i] < interiorMeanStrength * weakThresholdRatio) {
                positions[i] = slope * i + intercept
            }
        }

        // ④ 等間隔性を検証する。ズレが大きすぎる場合は信頼度を下げる
        val diffs = DoubleArray(expectedLineCount - 1) { i -> positions[i + 1] - positions[i] }
        val meanSpacing = diffs.average()
        val variance = diffs.map { (it - meanSpacing) * (it - meanSpacing) }.average()
        val stdDev = kotlin.math.sqrt(variance)
        // 標準偏差が平均間隔の10%を超えたら怪しい、というヒューリスティック
        val confidence = (1.0 - (stdDev / meanSpacing / 0.1)).coerceIn(0.0, 1.0)

        // ここで変数 result を作ってから返します
        val result = GridFitResult(positions, meanSpacing, confidence)
        // GridLineDetector.kt の return 直前あたり
        Log.d("DebugBoard", "検出結果: spacing=${result.spacing}, confidence=${result.confidence}, 線の数=${result.positions.size}")
        return GridFitResult(positions, meanSpacing, confidence)
    }

    // 最小二乗法による単回帰（罫線の位置 = slope * 路番号 + intercept）
    private fun linearRegression(xs: List<Double>, ys: List<Double>): Pair<Double, Double> {
        val n = xs.size
        val meanX = xs.average()
        val meanY = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (xs[i] - meanX) * (ys[i] - meanY)
            den += (xs[i] - meanX) * (xs[i] - meanX)
        }
        val slope = if (abs(den) < 1e-9) 0.0 else num / den
        val intercept = meanY - slope * meanX
        return Pair(slope, intercept)
    }

    private fun subpixelRefine(score: DoubleArray, idx: Int): Double {
        if (idx <= 0 || idx >= score.size - 1) return idx.toDouble()
        val y0 = score[idx - 1]; val y1 = score[idx]; val y2 = score[idx + 1]
        val denom = (y0 - 2 * y1 + y2)
        if (abs(denom) < 1e-6) return idx.toDouble()
        val offset = 0.5 * (y0 - y2) / denom
        return idx + offset
    }

    private fun rowSum(mat: Mat, row: Int): Double {
        var sum = 0.0
        val buf = FloatArray(mat.cols())
        mat.get(row, 0, buf)
        for (v in buf) sum += abs(v.toDouble())
        return sum
    }

    private fun colSum(mat: Mat, col: Int): Double {
        var sum = 0.0
        val buf = FloatArray(mat.rows())
        mat.get(0, col, buf)
        for (v in buf) sum += abs(v.toDouble())
        return sum
    }

    private fun Core_abs(mat: Mat) {
        org.opencv.core.Core.absdiff(mat, org.opencv.core.Scalar(0.0), mat)
    }

    enum class Axis { HORIZONTAL, VERTICAL }
}

/**
 * GridFitResultから、盤の論理的な端（罫線の最外周から半間隔外側）を計算する。
 * ここが今回のご提案の核心部分：物理的な端ではなく、罫線由来の座標を使う。
 */
object BoardEdgeFromGridLines {
    data class LogicalBoardRect(
        val topEdge: Double,
        val bottomEdge: Double,
        val leftEdge: Double,
        val rightEdge: Double
    )

    fun compute(
        horizontal: GridLineDetector.GridFitResult,
        vertical: GridLineDetector.GridFitResult
    ): LogicalBoardRect {
        val halfSpacingV = horizontal.spacing / 2.0
        val halfSpacingH = vertical.spacing / 2.0

        return LogicalBoardRect(
            topEdge = horizontal.positions.first() - halfSpacingV,
            bottomEdge = horizontal.positions.last() + halfSpacingV,
            leftEdge = vertical.positions.first() - halfSpacingH,
            rightEdge = vertical.positions.last() + halfSpacingH
        )
    }
}
