package com.github.tkirino.gobanreader.camera

import android.Manifest
import android.media.MediaActionSound
import android.util.Log
import android.util.Rational
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.github.tkirino.gobanreader.utility.GeometryUtils
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onStartReadingClick: (File) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    val imageCapture = remember { ImageCapture.Builder().build() }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) cameraPermissionState.launchPermissionRequest()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                // AndroidViewのfactory内を以下のように差し替えてください
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()

                            // 1. PreviewViewのアスペクト比に基づいたViewPortを作成
                            val viewPort = ViewPort.Builder(
                                Rational(this.width, this.height),
                                this.display.rotation
                            ).build()

                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }

                            // 2. UseCaseGroupを作成してバインド
                            val useCaseGroup = UseCaseGroup.Builder()
                                .addUseCase(preview)
                                .addUseCase(imageCapture)
                                .setViewPort(viewPort)
                                .build()

                            // 3. アスペクト比の確認用ログ（ここにブレークポイントを置いてください）
                            val screenAspect = this.width.toDouble() / this.height
                            Log.d("CameraConfig", "Screen Aspect: $screenAspect")

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup)
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                }
            )
        }

        // ガイドフレームの描画（UI上の目安）
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f) // 幅は画面の80%
                .aspectRatio(1f / 1.04f) // ★ここが重要！幅に対して高さを「1 : 1.04」で固定する
                .align(Alignment.Center)
                .border(2.dp, Color.White)
        )

        Button(onClick = onBackClick, modifier = Modifier.align(Alignment.TopStart).padding(20.dp)) {
            Text("戻る")
        }

        Button(
            onClick = {
                val photoFile = File(context.cacheDir, "goban_photo.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                sound.play(MediaActionSound.SHUTTER_CLICK)

                imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                    // onImageSaved 内の画像切り出し処理部分
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val src = Imgcodecs.imread(photoFile.absolutePath)

                        // ここで GeometryUtils を使用する
                        // src.width().toDouble(), src.height().toDouble() を渡して計算
                        // 今は引数が (width, height) のみですが、ここに offsetPercent を加えます
                        val guideRect = GeometryUtils.calculateGuideRect(
                            src.width().toDouble(),
                            src.height().toDouble(),
                            offsetPercent = 0.02 // ここで 2% の余裕を持たせます（まずはこの値で試してみてください）
                        )
                        // OpenCVのRectに変換して使用
                        val cvRect = org.opencv.core.Rect(
                            guideRect.x.toInt(),
                            guideRect.y.toInt(),
                            guideRect.width.toInt(),
                            guideRect.height.toInt()
                        )

                        val cropped = Mat()
                        src.submat(cvRect).copyTo(cropped)

                        saveDebugImage(cropped, "debug_roi_final.png")

                        onStartReadingClick(photoFile)
                    }
                    override fun onError(e: ImageCaptureException) {}
                })
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).size(76.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
        ) {}
    }
}
private fun saveDebugImage(mat: Mat, filename: String) {
    try {
        val file = File("/sdcard/Download/", filename)
        Imgcodecs.imwrite(file.absolutePath, mat)
    } catch (e: Exception) {
        Log.e("DebugLog", "Save failed: ${e.message}")
    }
}

