package com.github.tkirino.gobanreader.corner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.opencv.core.Point

@Composable
fun CornerScreen(
    bitmap: android.graphics.Bitmap,
    initialDetection: List<Point>,
    rawDetection: List<Point>, // 生データを受け取る
    onConfirmed: (List<Point>) -> Unit
) {
    var corners by remember(initialDetection) { mutableStateOf(initialDetection) }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewWidth = with(density) { maxWidth.toPx() }
        val viewHeight = with(density) { maxHeight.toPx() }

        val bitmapWidth = bitmap.width.toFloat()
        val bitmapHeight = bitmap.height.toFloat()
        val scale = minOf(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
        val offsetX = (viewWidth - bitmapWidth * scale) / 2
        val offsetY = (viewHeight - bitmapHeight * scale) / 2

        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                bitmap = imageBitmap,
                contentDescription = "Board",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            Canvas(modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        val touchPoint = change.position
                        val rawX = (touchPoint.x - offsetX) / scale
                        val rawY = (touchPoint.y - offsetY) / scale

                        val closestIndex = corners.indices.minByOrNull { i ->
                            val c = corners[i]
                            Math.hypot(c.x - rawX.toDouble(), c.y - rawY.toDouble())
                        } ?: return@detectDragGestures

                        if (Math.hypot(corners[closestIndex].x - rawX, corners[closestIndex].y - rawY) < 100.0) {
                            val newCorners = corners.toMutableList()
                            newCorners[closestIndex] = Point(
                                (corners[closestIndex].x + dragAmount.x / scale).coerceIn(0.0, bitmapWidth.toDouble()),
                                (corners[closestIndex].y + dragAmount.y / scale).coerceIn(0.0, bitmapHeight.toDouble())
                            )
                            corners = newCorners
                        }
                    }
                }
            ) {
                fun toOffset(p: Point) = Offset(
                    (p.x.toFloat() * scale) + offsetX,
                    (p.y.toFloat() * scale) + offsetY
                )

                // 1. Raw Coordinates (青い点) を描画
                rawDetection.forEach { point ->
                    drawCircle(color = Color.Blue, radius = 20f, center = toOffset(point))
                }

                // 2. 調整中の corners (赤い点) を描画
                corners.forEach { point ->
                    drawCircle(color = Color.Red, radius = 30f, center = toOffset(point))
                }
            }

            Button(
                onClick = { onConfirmed(corners) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            ) {
                Text("この範囲で確定")
            }
        }
    }
}
