package com.github.tkirino.gobanreader.corner

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.github.tkirino.gobanreader.utility.CornerUtils
import org.opencv.core.Point

@Composable
fun CornerScreen(
    bitmap: android.graphics.Bitmap,
    initialCorners: List<Point>,
    rawDetection: List<Point>,
    onConfirmed: (List<Point>) -> Unit
) {
    var corners by remember(initialCorners) { mutableStateOf(initialCorners) }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        val density = LocalDensity.current
        val viewWidth = with(density) { maxWidth.toPx() }
        val viewHeight = with(density) { maxHeight.toPx() }

        val bitmapWidth = bitmap.width.toFloat()
        val bitmapHeight = bitmap.height.toFloat()
        val scale = minOf(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
        val offsetX = (viewWidth - bitmapWidth * scale) / 2
        val offsetY = (viewHeight - bitmapHeight * scale) / 2

        // 1. 画像とドラッグ用Canvasを配置する領域
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                bitmap = imageBitmap,
                contentDescription = "Board",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var activeIndex: Int? = null

                        detectDragGestures(
                            onDragStart = { touchPoint ->
                                val rawX = (touchPoint.x.toDouble() - offsetX) / scale
                                val rawY = (touchPoint.y.toDouble() - offsetY) / scale

                                // タップされた位置に最も近いコーナーを操作対象として選択
                                activeIndex = corners.indices.minByOrNull { i ->
                                    val c = corners[i]
                                    Math.hypot(c.x - rawX, c.y - rawY)
                                }
                            },
                            onDrag = { change, dragAmount ->
                                val index = activeIndex ?: return@detectDragGestures

                                val deltaX = dragAmount.x.toDouble() / scale
                                val deltaY = dragAmount.y.toDouble() / scale

                                val newCorners = corners.toMutableList()
                                val current = newCorners[index]

                                newCorners[index] = Point(
                                    (current.x + deltaX).coerceIn(0.0, bitmapWidth.toDouble()),
                                    (current.y + deltaY).coerceIn(0.0, bitmapHeight.toDouble())
                                )
                                corners = newCorners
                            },
                            onDragEnd = { activeIndex = null },
                            onDragCancel = { activeIndex = null }
                        )
                    }
            ) {
                fun toOffset(p: Point) = Offset(
                    (p.x.toFloat() * scale) + offsetX.toFloat(),
                    (p.y.toFloat() * scale) + offsetY.toFloat()
                )

                // 四隅を繋ぐ枠線 (緑: ユーザー調整用)
                for (i in corners.indices) {
                    drawLine(
                        color = Color.Green,
                        strokeWidth = 5f,
                        start = toOffset(corners[i]),
                        end = toOffset(corners[(i + 1) % 4])
                    )
                }

                // Raw Detection (青い点)
                rawDetection.forEach { point ->
                    drawCircle(color = Color.Blue, radius = 20f, center = toOffset(point))
                }

                // 調整中の Corners (赤い十字デザイン)
                val markerRadius = 25f
                val crossHairLength = 40f
                val strokeWidth = 4f
                corners.forEach { point ->
                    val center = toOffset(point)
                    drawCircle(
                        color = Color.Red,
                        radius = markerRadius,
                        center = center,
                        style = Stroke(width = strokeWidth)
                    )
                    drawLine(
                        color = Color.Red,
                        start = Offset(center.x - crossHairLength, center.y),
                        end = Offset(center.x + crossHairLength, center.y),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = Color.Red,
                        start = Offset(center.x, center.y - crossHairLength),
                        end = Offset(center.x, center.y + crossHairLength),
                        strokeWidth = strokeWidth
                    )
                }
            }
        }

        // 2. 画面下部に配置する確定ボタン
        Button(
            onClick = {
                val expanded = CornerUtils.calculateExpandedCorners(corners)
                onConfirmed(expanded)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        ) {
            Text("この範囲で確定")
        }
    }
}
