package com.github.tkirino.gobanreader.camera

import android.Manifest
import android.media.MediaActionSound
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
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

    // カメラインスタンスとズーム倍率の状態管理
    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableStateOf(1.0f) }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) cameraPermissionState.launchPermissionRequest()
    }

    // スライダーで zoomRatio が変わったときにカメラのズームをリアルタイムに更新する
    LaunchedEffect(zoomRatio, cameraInstance) {
        cameraInstance?.cameraControl?.setZoomRatio(zoomRatio)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()

                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(surfaceProvider)
                            }

                            cameraProvider.unbindAll()

                            // カメラをバインドし、返されたカメラインスタンスを保持する
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture
                            )
                            cameraInstance = camera

                            // 起動時の初期ズームを適用
                            camera.cameraControl.setZoomRatio(zoomRatio)
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                }
            )
        }

        // ガイドフレーム
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .aspectRatio(1f / 1.04f)
                .align(Alignment.Center)
                .border(2.dp, Color.White)
        )

        // 画面下部にズーム調整用のスライダーを配置（0.5f 〜 3.0f）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 130.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = String.format("倍率: %.1f倍", zoomRatio),
                color = Color.White
            )
            Slider(
                value = zoomRatio,
                onValueChange = { newZoom ->
                    zoomRatio = newZoom
                },
                valueRange = 0.5f..3.0f
            )
        }

        Button(onClick = onBackClick, modifier = Modifier.align(Alignment.TopStart).padding(20.dp)) {
            Text("戻る")
        }

        Button(
            onClick = {
                val photoFile = File(context.cacheDir, "goban_photo.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                sound.play(MediaActionSound.SHUTTER_CLICK)

                imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        onStartReadingClick(photoFile)
                    }
                    override fun onError(e: ImageCaptureException) {
                        Log.e("CameraScreen", "撮影失敗: ${e.message}")
                    }
                })
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).size(76.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
        ) {}
    }
}
