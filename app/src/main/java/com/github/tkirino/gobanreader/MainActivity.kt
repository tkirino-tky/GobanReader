package com.github.tkirino.gobanreader

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.github.tkirino.gobanreader.ui.theme.GobanReaderTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    // ViewModelをActivity側でも共有して保持する
    private val viewModel: MainViewModel by viewModels()

    // 現在カメラ画面にいるかどうかを保持するフラグ（App.ktやNavHostから更新する、または簡易的に保持）
    var isInCameraScreen: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (OpenCVLoader.initDebug()) {
            Log.d("GobanReader", "OpenCV loaded successfully! 準備完了です。")
        } else {
            Log.e("GobanReader", "OpenCV load failed. ライブラリの読み込みに失敗しました。")
        }

        enableEdgeToEdge()
        setContent {
            GobanReaderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // MainActivityのインスタンスを渡すか、あるいはApp内で状態を同期させる
                    App(viewModel = viewModel, onCameraScreenChanged = { inCamera ->
                        isInCameraScreen = inCamera
                    })
                }
            }
        }
    }

    // ★最上流でキーイベントを横取り（ここで音量スライダーの暴発を防ぐ）
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isInCameraScreen) {
            val keyCode = event.keyCode
            val action = event.action

            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (action == KeyEvent.ACTION_DOWN) {
                    // カメラ画面にいる時は、ViewModel経由でシャッターを切るよう指示する
                    viewModel.triggerRemoteShutter()
                }
                // trueを返すことで、OSへのイベント伝播を止め、音量スライダーの表示を完全に防ぐ
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Preview
@Composable
fun Show() {
    Text("Hello")
}
