package com.github.tkirino.gobanreader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
@OptIn(ExperimentalMaterial3Api::class) // Add this annotation
@Composable
fun ReadResultScreen(
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
            Button(onClick = onBackClick) {
                Text("Go back to Setting")
            }
        }
    }
}
