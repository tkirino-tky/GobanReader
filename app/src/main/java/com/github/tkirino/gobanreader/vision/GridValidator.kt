package com.github.tkirino.gobanreader.vision

import org.opencv.core.Point
import kotlin.math.abs

data class ValidationResult(
    val isValid: Boolean,
    val warningMessage: String? = null
)

class GridValidator(
    private val maxAllowedOffsetPixels: Double = 15.0,
    private val errorRatioThreshold: Float = 0.05f
) {
    fun validate(
        geometryGrid: Array<Array<Point>>,
        detectedLinesX: DoubleArray,        // DoubleArray を直接受け取る
        detectedLinesY: DoubleArray         // DoubleArray を直接受け取る
    ): ValidationResult {
        val totalIntersections = 19 * 19
        var outlierCount = 0

        for (row in 0 until 19) {
            for (col in 0 until 19) {
                val ideal = geometryGrid[row][col]

                // DoubleArray に対する minByOrNull
                val nearestX = detectedLinesX.minByOrNull { abs(it - ideal.x) } ?: ideal.x
                val nearestY = detectedLinesY.minByOrNull { abs(it - ideal.y) } ?: ideal.y

                val offsetX = abs(ideal.x - nearestX)
                val offsetY = abs(ideal.y - nearestY)

                if (offsetX > maxAllowedOffsetPixels || offsetY > maxAllowedOffsetPixels) {
                    outlierCount++
                }
            }
        }

        val outlierRatio = outlierCount.toFloat() / totalIntersections

        if (outlierRatio > errorRatioThreshold) {
            return ValidationResult(
                isValid = false,
                warningMessage = "Grid validation warning: ${String.format("%.1f", outlierRatio * 100)}% of intersections deviate from detected lines."
            )
        }

        return ValidationResult(isValid = true)
    }
}
