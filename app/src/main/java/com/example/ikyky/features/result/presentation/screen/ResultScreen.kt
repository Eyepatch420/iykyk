package com.example.ikyky.features.result.presentation.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ikyky.features.result.presentation.viewmodel.ResultViewModel

@Composable
fun ResultScreen(
    sessionId: String,
    modifier: Modifier = Modifier,
    viewModel: ResultViewModel = viewModel(factory = LocalAppViewModelFactory.current),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    val name = "collage_$sessionId"
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text("${state.uniquePeople} people · ${state.totalAppearances} appearances")
        state.collage?.let { Image(it.asImageBitmap(), contentDescription = "Collage") }
        state.error?.let { Text("Error: ${it.message}") }
        Row {
            Button(enabled = !state.saving, onClick = { viewModel.onSave(name) }) { Text("Save") }
            Button(onClick = { viewModel.onShare(name) }, modifier = Modifier.padding(start = 8.dp)) {
                Text("Share")
            }
        }
        state.savedUri?.let { Text("Saved: $it") }
    }
}
