package com.example.ikyky.features.video_selection.presentation.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.features.video_selection.presentation.viewmodel.VideoSelectionViewModel

/**
 * Picks a video via the Android Photo Picker (no storage permission needed on
 * 33+, a scoped read on 26–32) and validates it before letting the user
 * continue to processing.
 */
@Composable
fun VideoSelectionScreen(
    onProceed: (uriString: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoSelectionViewModel = viewModel(factory = LocalAppViewModelFactory.current),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.onVideoPicked(uri.toString())
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Select a portrait video", style = MaterialTheme.typography.titleMedium)

        Button(onClick = {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }) {
            Text(if (state.selected == null) "Choose video" else "Choose a different video")
        }

        state.selected?.let { v ->
            Text(
                "${v.durationMs / 1000.0}s · ${v.width}×${v.height}" +
                    (if (v.rotationDegrees != 0) " · rot ${v.rotationDegrees}°" else ""),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        state.error?.let {
            Text(
                "Error: ${it.message}",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        Button(
            enabled = state.canProceed,
            onClick = { state.selected?.let { onProceed(it.uriString) } },
        ) {
            Text(if (state.isValidating) "Validating…" else "Continue")
        }
    }
}
