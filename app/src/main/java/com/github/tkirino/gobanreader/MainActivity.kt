package com.github.tkirino.gobanreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.github.tkirino.gobanreader.ui.App
import com.github.tkirino.gobanreader.ui.theme.GobanReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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