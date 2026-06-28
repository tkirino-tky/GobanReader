package com.github.tkirino.gobanreader.display

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.github.tkirino.gobanreader.model.StoneColor

@Composable
fun GoBoard(
    boardMatrix: List<List<StoneColor>>, // 【追加】19x19の盤面データを受け取る
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color(0xFFDCB35C)) // 碁盤の色
    ) {
        val boardSize = size.width
        val cellSize = boardSize / 20f
        val padding = cellSize

        // 1. 格子線（19本）の描画
        for (i in 0 until 19) {
            val offset = padding + (i * cellSize)
            // 横線
            drawLine(
                color = Color.Black,
                start = Offset(x = padding, y = offset),
                end = Offset(x = boardSize - padding, y = offset),
                strokeWidth = 1.5f
            )
            // 縦線
            drawLine(
                color = Color.Black,
                start = Offset(x = offset, y = padding),
                end = Offset(x = offset, y = boardSize - padding),
                strokeWidth = 1.5f
            )
        }

        // 2. 星（点）の描画
        val starIndices = listOf(3, 9, 15)
        val starRadius = cellSize * 0.1f
        for (row in starIndices) {
            for (col in starIndices) {
                drawCircle(
                    color = Color.Black,
                    radius = starRadius,
                    center = Offset(padding + (col * cellSize), padding + (row * cellSize))
                )
            }
        }

        // 3. 石の描画
        // マス目の92%くらいのサイズを石の直径にする（隣とわずかに隙間が空いてリアルになります）
        val stoneRadius = (cellSize * 0.92f) / 2f

        for (row in 0 until 19) {
            for (col in 0 until 19) {
                val stone = boardMatrix[row][col]
                if (stone != StoneColor.EMPTY) {
                    val cx = padding + (col * cellSize)
                    val cy = padding + (row * cellSize)

                    when (stone) {
                        StoneColor.BLACK -> {
                            drawCircle(
                                color = Color.Black,
                                radius = stoneRadius,
                                center = Offset(cx, cy)
                            )
                        }
                        StoneColor.WHITE -> {
                            // 白石本体
                            drawCircle(
                                color = Color.White,
                                radius = stoneRadius,
                                center = Offset(cx, cy)
                            )
                            // 白石が背景に埋もれないように薄いグレーのフチを引く
                            drawCircle(
                                color = Color.LightGray,
                                radius = stoneRadius,
                                center = Offset(cx, cy),
                                style = Stroke(width = 1f)
                            )
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}
