package com.github.tkirino.gobanreader

// --- OpenCVのインポートを追加 ---
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.github.tkirino.gobanreader.ui.theme.GobanReaderTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ========================================================
        // 【追加】OpenCVライブラリの初期化
        // ========================================================
        if (OpenCVLoader.initDebug()) {
            Log.d("GobanReader", "OpenCV loaded successfully! 準備完了です。")
        } else {
            Log.e("GobanReader", "OpenCV load failed. ライブラリの読み込みに失敗しました。")
        }
        enableEdgeToEdge()
        setContent {
            GobanReaderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    App()
                }
            }
        }
    }
}

@Preview
@Composable
fun Show() {
    Text("Hello")
}


//@Preview(showBackground = true)
//@Composable
//fun GreetingPreview() {
//    GobanReaderTheme {
//        Greeting("Android")
//    }
//}