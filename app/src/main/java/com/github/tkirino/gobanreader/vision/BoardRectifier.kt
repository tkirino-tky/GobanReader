package com.github.tkirino.gobanreader.vision

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.utils.Converters // これを追加

object BoardRectifier {

    fun rectify(src: Mat, corners: List<Point>): Mat {
        val boardSize = 1000.0
        val destPoints = listOf(
            Point(0.0, 0.0),
            Point(boardSize, 0.0),
            Point(boardSize, boardSize),
            Point(0.0, boardSize)
        )

        // Converters を使用
        val srcMat = Converters.vector_Point_to_Mat(corners)
        val dstMat = Converters.vector_Point_to_Mat(destPoints)

        val transform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
        val result = Mat()
        Imgproc.warpPerspective(src, result, transform, Size(boardSize, boardSize))

        srcMat.release()
        dstMat.release()
        transform.release()

        return result
    }
}
