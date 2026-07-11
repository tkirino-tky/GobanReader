package com.github.tkirino.gobanreader.utility

import org.opencv.core.Rect

object GeometryUtils {
    // offsetPercent を引数に追加しました
    fun calculateGuideRect(width: Double, height: Double, offsetPercent: Double = 0.0): Rect {
        val guideW = width * 0.8
        val guideH = guideW * 1.04

        // オフセット分を計算します
        val offsetW = width * offsetPercent
        val offsetH = height * offsetPercent

        // 元の枠をベースに、オフセット分だけ拡大（または縮小）させます
        val actualW = guideW + (offsetW * 2)
        val actualH = guideH + (offsetH * 2)

        val guideLeft = (width - actualW) / 2.0
        val guideTop = (height - actualH) / 2.0

        val margin = 2
        val x = (guideLeft + margin).toInt().coerceIn(0, width.toInt() - 1)
        val y = (guideTop + margin).toInt().coerceIn(0, height.toInt() - 1)
        val w = (actualW - margin * 2).toInt().coerceAtMost(width.toInt() - x)
        val h = (actualH - margin * 2).toInt().coerceAtMost(height.toInt() - y)

        return Rect(x, y, w, h)
    }
}
