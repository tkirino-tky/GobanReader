package com.github.tkirino.gobanreader.camera

import android.Manifest
import androidx.compose.ui.geometry.Rect
import android.media.MediaActionSound
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
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
    onStartReadingClick: (File, Rect) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    val imageCapture = remember { ImageCapture.Builder().build() }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    var currentPreviewSize by remember { mutableStateOf(IntSize.Zero) }
    var currentGuideBounds by remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) cameraPermissionState.launchPermissionRequest()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            AndroidView(
                modifier = Modifier.fillMaxSize().onGloballyPositioned { currentPreviewSize = it.size },
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .size(340.dp)
                .align(Alignment.Center)
                .border(2.dp, Color.White)
                .onGloballyPositioned { coordinates ->
                    val positionInRoot = coordinates.positionInRoot()
                    currentGuideBounds = Rect(
                        left = positionInRoot.x,
                        top = positionInRoot.y,
                        right = positionInRoot.x + coordinates.size.width,
                        bottom = positionInRoot.y + coordinates.size.height
                    )
                }
        )

        Button(onClick = onBackClick, modifier = Modifier.align(Alignment.TopStart).padding(20.dp)) {
            Text("戻る")
        }

        Button(
            // CameraScreen.kt のボタンの中の修正
            onClick = {
                if (currentPreviewSize == IntSize.Zero || currentGuideBounds == Rect.Zero) return@Button
                val photoFile = File(context.cacheDir, "goban_photo.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                sound.play(MediaActionSound.SHUTTER_CLICK)

                imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        // ここで Compose の Rect を渡している
                        onStartReadingClick(photoFile, currentGuideBounds)
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
