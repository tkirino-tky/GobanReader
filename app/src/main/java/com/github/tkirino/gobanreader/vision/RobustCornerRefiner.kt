package com.github.tkirino.gobanreader.vision

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * findContours で得た輪郭点列から、石が角に乗っている場合でも
 * 頑健に碁盤の4隅を求めるためのユーティリティ。
 *
 * 前提:
 * - Imgproc.findContours で碁盤の外枠輪郭を取得済み
 * - Imgproc.approxPolyDP や minAreaRect で粗い4隅の座標を取得済み
 *   (roughCorners は輪郭点列の順序と対応していること。時計回り/反時計回り
 *    どちらでもよいが、findContours が返す輪郭の点順序と一致している必要がある)
 *
 * 使い方:
 *   val contourPoints: List<Point> = contour.toArray().toList()    // MatOfPoint -> List<Point>
 *   val roughCorners: List<Point> = approxCurve.toArray().toList() // approxPolyDP の結果 (4点)
 *   val result = RobustCornerRefiner.refineBoardCorners(
 *       contourPoints, roughCorners, trimPixels = 20.0
 *   )
 *   val refinedCorners: List<Point?> = result.refinedCorners
 */
object RobustCornerRefiner {

    /** 直線を方向ベクトル(vx, vy)と通過点(x0, y0)で表す */
    data class Line(val vx: Double, val vy: Double, val x0: Double, val y0: Double)

    /** refineBoardCorners の戻り値をまとめたデータクラス */
    data class RefineResult(
        val refinedCorners: List<Point?>,
        val lines: List<Line>,
        val sides: List<List<Point>>
    )

    /**
     * 輪郭点列の中から、粗い4隅の座標に最も近い点のインデックスを求める。
     */
    fun findCornerIndices(contour: List<Point>, corners: List<Point>): List<Int> {
        return corners.map { corner ->
            var bestIdx = 0
            var bestDist = Double.MAX_VALUE
            for (i in contour.indices) {
                val dx = contour[i].x - corner.x
                val dy = contour[i].y - corner.y
                val dist = dx * dx + dy * dy
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = i
                }
            }
            bestIdx
        }
    }

    /**
     * 輪郭点列を4隅のインデックスを境目にして4つの辺(点群)に分割する。
     *
     * 注意: インデックスを昇順ソートしてから分割するため、
     * sides[i] は「輪郭上で sortedIdx[i] から sortedIdx[i+1] まで」の辺になる。
     * これは物理的な「上辺・右辺・下辺・左辺」の順序と一致するとは限らないため、
     * 必要であれば roughCorners の座標(x, y)から別途判定すること。
     */
    fun splitContourIntoSides(contour: List<Point>, cornerIndices: List<Int>): List<List<Point>> {
        val n = contour.size
        val sortedIdx = cornerIndices.sorted()

        return (0 until 4).map { i ->
            val start = sortedIdx[i]
            val end = sortedIdx[(i + 1) % 4]
            if (start < end) {
                contour.subList(start, end + 1).toList()
            } else {
                // 輪郭の終端(配列末尾)から先頭へまたぐケース
                contour.subList(start, n) + contour.subList(0, end + 1)
            }
        }
    }

    /**
     * 各辺の点群から、両端(角に近い側)を輪郭に沿った累積距離(ピクセル)で除去する。
     *
     * trimPixels は「グリッド1〜2マス分に相当するピクセル数」を目安に調整する。
     * 辺が短すぎてトリムできない場合や、トリムしすぎて点が5点未満になる場合は
     * 元の点群をそのまま返す(フォールバック)。
     */
    fun trimSidePoints(sidePoints: List<Point>, trimPixels: Double = 20.0): List<Point> {
        val n = sidePoints.size
        if (n < 5) return sidePoints

        val cumLen = DoubleArray(n)
        for (i in 1 until n) {
            val dx = sidePoints[i].x - sidePoints[i - 1].x
            val dy = sidePoints[i].y - sidePoints[i - 1].y
            cumLen[i] = cumLen[i - 1] + sqrt(dx * dx + dy * dy)
        }
        val totalLen = cumLen[n - 1]

        if (totalLen <= 2 * trimPixels) {
            // 辺が短すぎてトリムできない
            return sidePoints
        }

        val trimmed = sidePoints.filterIndexed { i, _ ->
            cumLen[i] >= trimPixels && cumLen[i] <= totalLen - trimPixels
        }

        return if (trimmed.size < 5) sidePoints else trimmed
    }

    /**
     * cv2.fitLine 相当の頑健な直線フィッティング。
     * distType には Imgproc.DIST_HUBER を推奨。トリムし損ねて残った
     * 石の輪郭点があっても、外れ値として自動的に重みが下がる。
     */
    fun fitRobustLine(points: List<Point>, distType: Int = Imgproc.DIST_HUBER): Line {
        val mat = MatOfPoint2f(*points.toTypedArray())
        val lineMat = Mat()
        try {
            Imgproc.fitLine(mat, lineMat, distType, 0.0, 0.01, 0.01)
            val data = FloatArray(4)
            lineMat.get(0, 0, data)
            return Line(
                vx = data[0].toDouble(),
                vy = data[1].toDouble(),
                x0 = data[2].toDouble(),
                y0 = data[3].toDouble()
            )
        } finally {
            mat.release()
            lineMat.release()
        }
    }

    /**
     * (vx, vy, x0, y0) 形式で表された2直線の交点を求める。
     * 2直線がほぼ平行で解けない場合は null を返す。
     */
    fun lineIntersection(l1: Line, l2: Line): Point? {
        // [ vx1  -vx2 ] [t]   [x2 - x1]
        // [ vy1  -vy2 ] [s] = [y2 - y1]
        val a11 = l1.vx
        val a12 = -l2.vx
        val a21 = l1.vy
        val a22 = -l2.vy
        val b1 = l2.x0 - l1.x0
        val b2 = l2.y0 - l1.y0

        val det = a11 * a22 - a12 * a21
        if (abs(det) < 1e-9) {
            return null // ほぼ平行
        }
        val t = (b1 * a22 - a12 * b2) / det
        return Point(l1.x0 + t * l1.vx, l1.y0 + t * l1.vy)
    }

    /**
     * findContours の輪郭と粗い4隅から、石の影響を排除した頑健な4隅を求めるメイン処理。
     *
     * @param contour 輪郭点列 (findContours の1輪郭を List<Point> に変換したもの)
     * @param roughCorners 粗い4隅 (approxPolyDP や minAreaRect の結果、4点)
     * @param trimPixels 角から除外する累積距離(ピクセル)。グリッド1〜2マス分を目安に調整
     * @param distType fitLine の距離関数。Imgproc.DIST_HUBER 推奨
     */
    fun refineBoardCorners(
        contour: List<Point>,
        roughCorners: List<Point>,
        trimPixels: Double = 20.0,
        distType: Int = Imgproc.DIST_HUBER
    ): RefineResult {
        require(roughCorners.size == 4) { "roughCorners は4点である必要があります" }

        val cornerIndices = findCornerIndices(contour, roughCorners)
        val sides = splitContourIntoSides(contour, cornerIndices)

        val lines = sides.map { side ->
            val trimmed = trimSidePoints(side, trimPixels)
            fitRobustLine(trimmed, distType)
        }

        // sides[i] は sortedIdx[i] -> sortedIdx[i+1] の辺なので、
        // 隣接2辺 (lines[i-1], lines[i]) の交点が sortedIdx[i] に対応する角になる。
        val refinedCorners = (0 until 4).map { i ->
            val lPrev = lines[(i - 1 + 4) % 4]
            val lCurr = lines[i]
            lineIntersection(lPrev, lCurr)
        }

        return RefineResult(refinedCorners, lines, sides)
    }
}
