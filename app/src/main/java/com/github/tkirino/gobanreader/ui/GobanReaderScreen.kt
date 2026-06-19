package com.github.tkirino.gobanreader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class) // これを付けるだけで解決します
@Composable
fun GobanReaderScreen(
    onStartReadingClick: () -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Goban Reader - Reading") }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            Text("This is GobanReaderScreen")
            Button(onClick = onStartReadingClick) {
                Text("Go to Reading Result")
            }
            Button(onClick = onBackClick) {
                Text("Go back to Setting")
            }
        }
    }
}
