package com.example.ikyky.features.processing.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.features.processing.domain.model.ProcessingDiagnostics
import com.example.ikyky.features.processing.domain.model.ProcessingStage
import com.example.ikyky.features.processing.presentation.viewmodel.ProcessingViewModel

/**
 * Drives one processing run and shows real progress + live diagnostics.
 * All heavy work is inside the ViewModel's use case (background dispatcher);
 * this composable only renders state and offers Cancel / Retry.
 */
@Composable
fun ProcessingScreen(
    sessionId: String,
    uriString: String,
    onFinished: (sessionId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProcessingViewModel = viewModel(
        factory = LocalAppViewModelFactory.current.forSession(sessionId),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uriString) { viewModel.start(uriString) }
    LaunchedEffect(state.finished) { if (state.finished) onFinished(sessionId) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stageLabel(state.stage), style = MaterialTheme.typography.titleMedium)

        LinearProgressIndicator(
            progress = { state.fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        state.detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }

        DiagnosticsPanel(state.diagnostics)

        state.error?.let { err ->
            Text(
                "Error: ${err.message}",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        when {
            state.running ->
                OutlinedButton(onClick = viewModel::cancel) { Text("Cancel") }
            state.stage == ProcessingStage.ERROR ->
                Button(onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
private fun DiagnosticsPanel(d: ProcessingDiagnostics) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val style = MaterialTheme.typography.bodySmall
        Text("frames  ${d.framesSampled} / ${d.framesPlanned}   (decoded ${d.framesDecodedOk}, invalid ${d.framesRejectedAsInvalid})", style = style)
        Text("faces   ${d.totalFaceObservations} obs · ${d.framesWithFaces} frames with faces · ${d.multiFaceFrames} multi-face · max ${d.maxFacesInAnyFrame}", style = style)
        Text("quality ${d.observationsLowQuality} low-quality obs (still tracked)", style = style)
        Text("tracklets raw ${d.rawTracklets} − filtered ${d.trackletsFilteredOut} = ${d.trackletsAfterFilter}   appearances ${d.appearancesDetected}", style = style)
        if (d.totalProcessingMs > 0) {
            Text("time ${d.totalProcessingMs} ms total · ${"%.1f".format(d.avgFrameProcessingMs)} ms/frame", style = style)
        }
    }
}

private fun stageLabel(stage: ProcessingStage): String = when (stage) {
    ProcessingStage.LOADING_VIDEO -> "Loading video…"
    ProcessingStage.EXTRACTING_FRAMES -> "Extracting frames…"
    ProcessingStage.DETECTING_FACES -> "Detecting faces…"
    ProcessingStage.TRACKING_APPEARANCES -> "Tracking faces…"
    ProcessingStage.FINALIZING_APPEARANCES -> "Finalizing appearances…"
    ProcessingStage.COMPLETED -> "Done"
    ProcessingStage.ERROR -> "Failed"
    else -> stage.name
}
