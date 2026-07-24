package com.github.tkirino.gobanreader.corner

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.github.tkirino.gobanreader.vision.GridLineDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment

@Composable
fun CornerScreen(
    bitmap: android.graphics.Bitmap,
    initialCorners: List<Point>,
    rawDetection: List<Point>,
    onConfirmed: (List<Point>) -> Unit
) {
    var corners by remember(initialCorners) { mutableStateOf(initialCorners) }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    // --- デバッグ確認用の状態管理 ---
    val coroutineScope = rememberCoroutineScope()
    var isDebugging by remember { mutableStateOf(false) }
    var tempExpandedCorners by remember { mutableStateOf<List<Point>?>(null) }

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
                        if (!isDebugging) {
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

                                    // 指で隠れないよう、画面上の移動量(dragAmount)を画像上の移動量に変換して加算
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

                // 「この範囲で確定」が押された後の5秒間のみ、拡張された赤枠を表示
                if (isDebugging) {
                    val currentExpanded = tempExpandedCorners
                    if (currentExpanded != null && currentExpanded.size == 4) {
                        for (i in currentExpanded.indices) {
                            drawLine(
                                color = Color.Red,
                                strokeWidth = 5f,
                                start = toOffset(currentExpanded[i]),
                                end = toOffset(currentExpanded[(i + 1) % 4])
                            )
                        }
                    }
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
        val context = androidx.compose.ui.platform.LocalContext.current
        Button(
            onClick = {
                val expanded = CornerUtils.calculateExpandedCorners(corners)
                tempExpandedCorners = expanded
                testGridLineDetection(context, bitmap, expanded)

                if (!isDebugging) {
                    isDebugging = true
                    coroutineScope.launch {
                        delay(5000L)
                        onConfirmed(expanded)
                    }
                }
            },
            enabled = !isDebugging,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        ) {
            Text(if (isDebugging) "拡張枠を確認中... (5秒後に進みます)" else "この範囲で確定")
        }
    }
}

fun testGridLineDetection(context: Context, originalBitmap: Bitmap, expandedCorners: List<Point>) {
    if (expandedCorners.size != 4) return

    val srcMat = Mat()
    Utils.bitmapToMat(originalBitmap, srcMat)

    val warpSize = 800.0
    val srcPoints = MatOfPoint2f(
        expandedCorners[0],
        expandedCorners[1],
        expandedCorners[2],
        expandedCorners[3]
    )
    val dstPoints = MatOfPoint2f(
        Point(0.0, 0.0),
        Point(warpSize, 0.0),
        Point(warpSize, warpSize),
        Point(0.0, warpSize)
    )

    val perspectiveTransform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
    val warpedColorMat = Mat()
    Imgproc.warpPerspective(srcMat, warpedColorMat, perspectiveTransform, Size(warpSize, warpSize))

    val warpedGrayMat = Mat()
    Imgproc.cvtColor(warpedColorMat, warpedGrayMat, Imgproc.COLOR_RGB2GRAY)

    val detector = GridLineDetector()
    val hResult = detector.detectGridLines(warpedGrayMat, GridLineDetector.Axis.HORIZONTAL)
    val vResult = detector.detectGridLines(warpedGrayMat, GridLineDetector.Axis.VERTICAL)

    if (hResult != null) {
        for (y in hResult.positions) {
            Imgproc.line(warpedColorMat, Point(0.0, y), Point(warpSize, y), Scalar(0.0, 255.0, 0.0), 2)
        }
    }

    if (vResult != null) {
        for (x in vResult.positions) {
            Imgproc.line(warpedColorMat, Point(x, 0.0), Point(x, warpSize), Scalar(255.0, 0.0, 0.0), 2)
        }
    }

    Imgproc.cvtColor(warpedColorMat, warpedColorMat, Imgproc.COLOR_RGB2BGR)

    val filename = "grid_test_${System.currentTimeMillis()}.png"
    val resolver = context.contentResolver
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    imageUri?.let { uri ->
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                val matOfByte = org.opencv.core.MatOfByte()
                val success = Imgcodecs.imencode(".png", warpedColorMat, matOfByte)

                if (success && !matOfByte.empty()) {
                    val bytes = matOfByte.toArray()
                    stream.write(bytes)
                    stream.flush()
                }
                matOfByte.release()
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

        } catch (e: Exception) {
            Log.e("GridTest", "ファイルの書き込みに失敗しました", e)
        }
    }

    srcMat.release()
    srcPoints.release()
    dstPoints.release()
    perspectiveTransform.release()
    warpedGrayMat.release()
    warpedColorMat.release()
}
