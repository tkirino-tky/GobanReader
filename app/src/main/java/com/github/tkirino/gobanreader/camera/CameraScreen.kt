package com.github.tkirino.gobanreader.camera

import android.Manifest
import android.media.MediaActionSound
import android.util.Log
import android.util.Rational
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    val zoomManager = remember { CameraZoomManager(context) }
    var currentZoom by remember { mutableStateOf(zoomManager.defaultZoomRatio) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // 端末がズーム（超広角等）をサポートしているかどうか
    var isZoomSupported by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) cameraPermissionState.launchPermissionRequest()
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
                            cameraProviderRef = cameraProvider

                            isZoomSupported = zoomManager.isZoomOutSupported(cameraProvider)

                            val viewPort = ViewPort.Builder(
                                Rational(this.width, this.height),
                                this.display.rotation
                            ).build()

                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }

                            val useCaseGroup = UseCaseGroup.Builder()
                                .addUseCase(preview)
                                .addUseCase(imageCapture)
                                .setViewPort(viewPort)
                                .build()

                            cameraProvider.unbindAll()
                            zoomManager.bindCameraWithZoom(
                                cameraProvider,
                                lifecycleOwner,
                                useCaseGroup,
                                currentZoom
                            ) { _ -> }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                update = { previewView ->
                    cameraProviderRef?.let { cameraProvider ->
                        try {
                            val viewPort = ViewPort.Builder(
                                Rational(previewView.width, previewView.height),
                                previewView.display.rotation
                            ).build()

                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

                            val useCaseGroup = UseCaseGroup.Builder()
                                .addUseCase(preview)
                                .addUseCase(imageCapture)
                                .setViewPort(viewPort)
                                .build()

                            cameraProvider.unbindAll()
                            zoomManager.bindCameraWithZoom(
                                cameraProvider,
                                lifecycleOwner,
                                useCaseGroup,
                                currentZoom
                            ) { _ -> }
                        } catch (e: Exception) {
                            Log.e("CameraScreen", "倍率変更時の再バインド失敗: ${e.message}")
                        }
                    }
                }
            )
        }

        // 碁盤枠ガイド
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .aspectRatio(1f / 1.04f)
                .align(Alignment.Center)
                .border(2.dp, Color.White)
        )

        // 戻るボタン
        Button(onClick = onBackClick, modifier = Modifier.align(Alignment.TopStart).padding(20.dp)) {
            Text("戻る")
        }

        // 倍率変更ボタン群：非対応端末でも表示しつつ、グレイアウト（操作無効）にする
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CameraZoomManager.SUPPORTED_ZOOMS.forEach { ratio ->
                val isSelected = (currentZoom == ratio)
                Button(
                    onClick = {
                        if (isZoomSupported) {
                            currentZoom = ratio
                            zoomManager.defaultZoomRatio = ratio
                        }
                    },
                    enabled = isZoomSupported, // 非対応時は自動でグレイアウト＆タップ無効になる
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) Color.White else Color.Black.copy(alpha = 0.5f),
                        contentColor = if (isSelected) Color.Black else Color.White,
                        disabledContainerColor = Color.Gray.copy(alpha = 0.3f),
                        disabledContentColor = Color.LightGray
                    ),
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(text = "${ratio}x", fontSize = 12.sp)
                }
            }
        }

        // シャッターボタン
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .size(80.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
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
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.8f),
                    radius = size.minDimension / 2f,
                    style = Stroke(width = 6.dp.toPx())
                )
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.White, CircleShape)
            )
        }
    }
}
