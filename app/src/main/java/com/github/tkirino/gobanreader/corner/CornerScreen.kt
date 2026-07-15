package com.github.tkirino.gobanreader.corner

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.opencv.core.Point

@Composable
fun CornerScreen(
    bitmap: Bitmap,
    initialDetection: List<Point>,
    onConfirmed: (List<Point>) -> Unit
) {
    var corners by remember { mutableStateOf(initialDetection) }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    val touchPoint = Offset(change.position.x, change.position.y)
                    val closestIndex = corners.indices.minByOrNull { i ->
                        val c = corners[i]
                        Math.hypot(c.x - touchPoint.x, c.y - touchPoint.y)
                    } ?: return@detectDragGestures

                    // ハンドル範囲内であればドラッグを反映
                    if (Math.hypot(corners[closestIndex].x - touchPoint.x, corners[closestIndex].y - touchPoint.y) < 150.0) {
                        val newCorners = corners.toMutableList()
                        newCorners[closestIndex] = Point(
                            (corners[closestIndex].x + dragAmount.x).coerceIn(0.0, bitmap.width.toDouble()),
                            (corners[closestIndex].y + dragAmount.y).coerceIn(0.0, bitmap.height.toDouble())
                        )
                        corners = newCorners
                    }
                }
            }
        ) {
            // 背景画像の描画
            drawImage(imageBitmap)

            // 四隅を繋ぐ枠線の描画
            val path = Path().apply {
                moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
                lineTo(corners[1].x.toFloat(), corners[1].y.toFloat())
                lineTo(corners[2].x.toFloat(), corners[2].y.toFloat())
                lineTo(corners[3].x.toFloat(), corners[3].y.toFloat())
                close()
            }
            drawPath(path, Color.Green, style = Stroke(width = 5f))

            // 四隅のハンドルの描画
            corners.forEach { point ->
                drawCircle(color = Color.Red, radius = 30f, center = Offset(point.x.toFloat(), point.y.toFloat()))
            }
        }

        // 確定ボタン
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
