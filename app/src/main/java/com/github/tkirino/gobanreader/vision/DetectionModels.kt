package com.github.tkirino.gobanreader.vision

import org.opencv.core.Point

// 1. 直線の幾何学的表現（既存のLineクラスの代わり、または強化版として）
data class LineEquation(val a: Double, val b: Double, val c: Double)

// 2. LSD等から抽出された線分候補
data class LineCandidate(
    val lineEq: LineEquation,
    val score: Double,        // 検出時の強度や長さに基づく評価値
    val p1: Point,            // セグメントの始点
    val p2: Point             // セグメントの終点
)

// 3. 各辺のROIから抽出された候補リスト
data class EdgeCandidates(
    val type: EdgeType,       // TOP, BOTTOM, LEFT, RIGHT
    val candidates: List<LineCandidate>
)

// 4. 最終的に確定した盤面の幾何情報
data class OptimizedBoard(
    val top: LineCandidate,
    val bottom: LineCandidate,
    val left: LineCandidate,
    val right: LineCandidate,
    val score: Double         // 4辺の整合性（直交性や長方形度）に基づく総合スコア
)

enum class EdgeType {
    TOP, BOTTOM, LEFT, RIGHT
}
