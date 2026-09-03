package com.example.ikyky.features.collage.presentation.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ikyky.features.collage.presentation.viewmodel.CollageViewModel

@Composable
fun CollageScreen(
    sessionId: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollageViewModel = viewModel(factory = LocalAppViewModelFactory.current),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        state.collage?.let { Image(it.asImageBitmap(), contentDescription = "Collage") }
        state.error?.let { Text("Error: ${it.message}") }
        Button(
            enabled = !state.generating,
            onClick = { viewModel.generate(sessionId, width = 1080, height = 1920) },
        ) {
            Text(if (state.generating) "Generating…" else "Generate collage")
        }
        Button(enabled = state.collage != null, onClick = onDone) { Text("Continue") }
    }
}
