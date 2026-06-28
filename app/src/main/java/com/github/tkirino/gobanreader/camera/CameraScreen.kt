package com.github.tkirino.gobanreader.camera

import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.github.tkirino.gobanreader.GuideFrame
import com.github.tkirino.gobanreader.GuidedBoardDetector
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

@Composable
fun CameraScreen(
    onStartReadingClick: () -> Unit,
    onBackClick: () -> Unit
) {
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var isCaptured by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isCaptured) {
            CameraPreviewScreen(
                modifier = Modifier.fillMaxSize(),
                onCaptureSuccess = { photoFile ->
                    capturedFile = photoFile
                    isCaptured = true
                }
            )
        } else {
            ResultScreen(
                photoFile = capturedFile,
                onBackToCamera = {
                    isCaptured = false
                    capturedFile = null
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 切り取られた画像の中から、最もそれっぽい「碁盤の4隅の座標」を自動で探す関数
 * 見つからない場合は、画像の4隅（デフォルト）を返す
 */
fun findBoardCorners(srcMat: Mat): List<Point> {
    val sizeX = srcMat.cols().toDouble()
    val sizeY = srcMat.rows().toDouble()

    // 次のステップでここに数理計算を入れます。
    // 今は碁石の検出テストに専念するため、一旦デフォルトの4隅を返します。
    return listOf(
        Point(0.0, 0.0), Point(sizeX, 0.0), Point(sizeX, sizeY), Point(0.0, sizeY)
    )
}

/**
 * 2つの線分 (x1,y1)-(x2,y2) と (x3,y3)-(x4,y4) の無限延長線上における交点を計算するヘルパー関数
 */
private fun computeIntersection(line1: DoubleArray, line2: DoubleArray): Point? {
    val x1 = line1[0]; val y1 = line1[1]; val x2 = line1[2]; val y2 = line1[3]
    val x3 = line2[0]; val y3 = line2[1]; val x4 = line2[2]; val y4 = line2[3]

    val denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
    if (Math.abs(denom) < 1e-5) return null // 平行なため交点なし

    val px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / denom
    val py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / denom

    return Point(px, py)
}

/**
 * 検出された4つの頂点を [左上, 右上, 右下, 左下] の順番に整列させる補助関数
 */
fun sortCorners(points: List<Point>): List<Point> {
    // X+Yの合計が最も小さいのが左上、最も大きいのが右下
    val topLeft = points.minByOrNull { it.x + it.y } !!
    val bottomRight = points.maxByOrNull { it.x + it.y } !!

    // X-Yの差が最も小さい（Yが大きい）のが左下、差が最も大きいのが右上
    val topRight = points.maxByOrNull { it.x - it.y } !!
    val bottomLeft = points.minByOrNull { it.x - it.y } !!

    return listOf(topLeft, topRight, bottomRight, bottomLeft)
}

/**
 * 画面上の「白いガイド枠（長方形）」が見ていた範囲を、実際のカメラ画像から極めて正確に切り出し、
 * 1000x1073の長方形に射影変換（Warp）する関数
 */
fun processBoardImage(photoFile: File, previewSize: IntSize, guideSizePx: Float): Mat? {
    val srcMat = Imgcodecs.imread(photoFile.absolutePath)
    if (srcMat.empty()) return null

    // ここで引数を受け取ったので、GuidedBoardDetectorが使えるようになります
    // ※先ほど修正したクラス定義が前提です
    val detector = GuidedBoardDetector(previewSize, guideSizePx)

    // ガイド枠を使って処理を実行
    return detector.process(photoFile)
}

// 2点間のユークリッド距離を求めるヘルパー関数
fun mathDistance(p1: Point, p2: Point): Double {
    val dx = p1.x - p2.x
    val dy = p1.y - p2.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

@Composable
fun CameraPreviewScreen(
    modifier: Modifier = Modifier,
    onCaptureSuccess: (File) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val sound = remember { MediaActionSound() }

    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var guideSizePx by remember { mutableStateOf(0f) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // --- 【修正ここから】 ---
    // コールバック内で安全に使えるように、現在の値をローカル変数にコピーします
    val currentPreviewSize = previewSize
    val currentGuideSize = guideSizePx
    // --- 【修正ここまで】 ---

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                previewSize = coordinates.size
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch (exc: Exception) {
                        Log.e("CameraPreview", "Binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        Box(
            modifier = Modifier
                .width(340.dp)
                .aspectRatio(1f / 1.073f)
                .align(Alignment.Center)
                .border(2.dp, Color.White)
                .onGloballyPositioned { coordinates ->
                    guideSizePx = coordinates.size.width.toFloat()
                }
        )

        Button(
            onClick = {
                if (currentPreviewSize == IntSize.Zero || currentGuideSize == 0f) return@Button

                val photoFile = File(context.cacheDir, "goban_photo.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                sound.play(MediaActionSound.SHUTTER_CLICK)

                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            Thread {
                                // --- 【修正箇所】 ---
                                // 退避させておいた変数（currentPreviewSize, currentGuideSize）を渡す
                                processBoardImage(photoFile, currentPreviewSize, currentGuideSize)

                                Handler(Looper.getMainLooper()).post {
                                    onCaptureSuccess(photoFile)
                                }
                            }.start()
                        }
                        override fun onError(exception: ImageCaptureException) {
                            Toast.makeText(context, "エラー: ${exception.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .size(76.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
        ) {}
    }
}


@Composable
fun ResultScreen(
    photoFile: File?,
    onBackToCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    var bitmapState by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(photoFile) {
        if (photoFile != null && photoFile.exists()) {
            photoFile.inputStream().use { stream ->
                bitmapState = BitmapFactory.decodeStream(stream)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(text = "【解析結果】正しく認識されているか確認してください", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))

        if (bitmapState != null) {
            Box(modifier = Modifier.size(350.dp)) {
                Image(
                    bitmap = bitmapState!!.asImageBitmap(),
                    contentDescription = "解析結果画像",
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Text(text = "画像を読み込み中...")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onBackToCamera) {
            Text(text = "もう一度撮影する")
        }
    }
}

