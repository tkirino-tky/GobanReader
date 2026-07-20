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
import java.io.File
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import java.io.OutputStream

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
                        // デバッグ確認中はドラッグ操作を受け付けないようにする
                        if (!isDebugging) {
                            detectDragGestures { change, dragAmount ->
                                val touchPoint = change.position
                                val rawX = (touchPoint.x.toDouble() - offsetX) / scale
                                val rawY = (touchPoint.y.toDouble() - offsetY) / scale

                                val closestIndex = corners.indices.minByOrNull { i ->
                                    val c = corners[i]
                                    Math.hypot(c.x - rawX, c.y - rawY)
                                } ?: return@detectDragGestures

                                if (Math.hypot(
                                        corners[closestIndex].x - rawX,
                                        corners[closestIndex].y - rawY
                                    ) < 100.0
                                ) {
                                    val newCorners = corners.toMutableList()
                                    newCorners[closestIndex] = Point(
                                        (corners[closestIndex].x + dragAmount.x.toDouble() / scale).coerceIn(
                                            0.0,
                                            bitmapWidth.toDouble()
                                        ),
                                        (corners[closestIndex].y + dragAmount.y.toDouble() / scale).coerceIn(
                                            0.0,
                                            bitmapHeight.toDouble()
                                        )
                                    )
                                    corners = newCorners
                                }
                            }
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

                // ★ 「この範囲で確定」が押された後の5秒間のみ、拡張された赤枠を画面に描画して表示する
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
                // 1. デバッグ中かどうかにかかわらず、必ず拡張座標を計算する
                val expanded = CornerUtils.calculateExpandedCorners(corners)
                tempExpandedCorners = expanded

                // 2. テスト用に関数を呼び出す（拡張された座標を使ってGridLineDetectorを走らせる）
                testGridLineDetection(context, bitmap, expanded)

                if (!isDebugging) {
                    // 3. デバッグ表示モードをONにして赤枠を出現させる
                    isDebugging = true

                    // 4. 5秒間停止したあと、次の画面へ遷移する
                    coroutineScope.launch {
                        delay(5000L) // 5秒間停止して目視確認
                        onConfirmed(expanded) // 計算済みの拡張座標をそのまま渡す
                    }
                }
            },
            enabled = !isDebugging, // 確認中はボタンを無効化
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

    // 色空間をRGBに戻す
    Imgproc.cvtColor(warpedColorMat, warpedColorMat, Imgproc.COLOR_RGB2BGR)

    // Androidの MediaStore を使って共有の「Pictures」フォルダに保存する
    val filename = "grid_test_${System.currentTimeMillis()}.png"

    val resolver = context.contentResolver
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        // Android 10以降では、最初は「ペンディング（書き込み中）」状態にする必要がある
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
                    Log.d("GridTest", "画像をPicturesフォルダに書き込みました: ${bytes.size} bytes")
                } else {
                    Log.e("GridTest", "imencodeに失敗したか、データが空です")
                }
                matOfByte.release()
            }

            // 書き込みが完了したので、ペンディング状態を解除してファイルを有効化する
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            Log.d("GridTest", "画像をPicturesフォルダに保存しました: $filename")

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
