package com.example.ikyky.features.video_selection.presentation.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.core.ui.components.AppScreen
import com.example.ikyky.core.ui.components.PrimaryButton
import com.example.ikyky.core.ui.components.SecondaryButton
import com.example.ikyky.core.ui.theme.Spacing
import com.example.ikyky.features.video_selection.presentation.viewmodel.VideoSelectionViewModel

/**
 * The entry point — one concise product line, three-step framing, one large
 * primary action. Picks a video via the Android Photo Picker (no storage
 * permission on 33+, a scoped read on 26–32) and validates it before enabling
 * "Continue". Behaviour unchanged from Phase 1; only the surface is new.
 */
@Composable
fun VideoSelectionScreen(
    onProceed: (uriString: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoSelectionViewModel = viewModel(factory = LocalAppViewModelFactory.current),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) viewModel.onVideoPicked(uri.toString()) }

    fun launchPicker() = picker.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
    )

    AppScreen(modifier = modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.weight(0.9f))

            Text(
                "iykyk",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                "Turn a video into memories.",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "Pick a clip and we'll find the people in it, then lay them out as a collage.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(0.9f),
            )

            state.selected?.let { v ->
                Spacer(Modifier.height(Spacing.xl))
                SelectedVideoRow(
                    seconds = v.durationMs / 1000.0,
                    width = v.width,
                    height = v.height,
                )
            }
            state.error?.let { err ->
                Spacer(Modifier.height(Spacing.md))
                Text(
                    err.message ?: "That video didn't work. Try another one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.weight(1.1f))

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (state.selected == null) {
                    PrimaryButton(
                        text = "Choose a video",
                        onClick = ::launchPicker,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    PrimaryButton(
                        text = if (state.isValidating) "Checking…" else "Continue",
                        enabled = state.canProceed,
                        onClick = { state.selected?.let { onProceed(it.uriString) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecondaryButton(
                        text = "Choose a different video",
                        onClick = ::launchPicker,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

@Composable
private fun SelectedVideoRow(seconds: Double, width: Int, height: Int) {
    Column {
        Text(
            "Video selected",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            "%.0fs · %d × %d".format(seconds, width, height),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
