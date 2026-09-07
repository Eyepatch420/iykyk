package com.example.ikyky.features.processing.presentation.screen

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.core.ui.components.AppScreen
import com.example.ikyky.core.ui.components.MessageState
import com.example.ikyky.core.ui.components.TertiaryButton
import com.example.ikyky.core.ui.theme.AppShape
import com.example.ikyky.core.ui.theme.Dimensions
import com.example.ikyky.core.ui.theme.Spacing
import com.example.ikyky.features.processing.domain.model.ProcessingStage
import com.example.ikyky.features.processing.presentation.viewmodel.ProcessingViewModel

/**
 * Calm, user-facing progress. One warm line ("Finding the people in your
 * video…"), the current step in plain words, a thin known-progress bar, and a
 * quiet "N of M" counter parsed from the pipeline's own progress detail.
 *
 * NO diagnostics on screen — observations / tracklets / embeddings / clustering
 * stay in logs only. Cancel is present but visually secondary.
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

    AppScreen(modifier = modifier) {
        if (state.stage == ProcessingStage.ERROR) {
            MessageState(
                title = if (state.cancelled) "Processing stopped"
                else "We couldn't process this video.",
                body = if (state.cancelled) "Pick a video whenever you're ready."
                else "It may be too short, or the faces may be hard to see. Try another one.",
                actionLabel = if (state.cancelled) "Back" else "Choose another video",
                onAction = onBack,
            )
            return@AppScreen
        }

        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            BreathingDot()
            Spacer(Modifier.height(Spacing.xl))

            Text(
                "Finding the people in your video",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                friendlyStage(state.stage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Spacing.xl))
            LinearProgressIndicator(
                progress = { state.fraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(Dimensions.progressTrack)
                    .clip(AppShape.pill),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            counterOf(state.detail)?.let { (done, total) ->
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "$done of $total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.weight(1f))

            if (state.running) {
                TertiaryButton(text = "Cancel", onClick = viewModel::cancel)
            } else if (state.cancelled) {
                TertiaryButton(text = "Back", onClick = onBack)
            }
            Spacer(Modifier.height(Spacing.md))
        }
    }
}

/** A slow, quiet pulse — communicates "working", not a flashy spinner. */
@Composable
private fun BreathingDot() {
    val t = rememberInfiniteTransition(label = "breathe")
    val scale by t.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "scale",
    )
    val alpha by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "alpha",
    )
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(Modifier.height(16.dp).fillMaxWidth()) {
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = size.minDimension / 2f * scale,
            center = center,
        )
    }
}

private fun friendlyStage(stage: ProcessingStage): String = when (stage) {
    ProcessingStage.LOADING_VIDEO -> "Opening your video"
    ProcessingStage.EXTRACTING_FRAMES -> "Scanning the video"
    ProcessingStage.DETECTING_FACES -> "Looking for faces"
    ProcessingStage.TRACKING_APPEARANCES -> "Following each face"
    ProcessingStage.FINALIZING_APPEARANCES -> "Putting it together"
    ProcessingStage.COMPLETED -> "Done"
    ProcessingStage.ERROR -> "Something went wrong"
    else -> "Working"
}

/** Pulls a "done / total" pair out of the pipeline's progress detail string. */
private fun counterOf(detail: String?): Pair<Int, Int>? {
    if (detail == null) return null
    val m = Regex("(\\d+)\\s*/\\s*(\\d+)").find(detail) ?: return null
    val a = m.groupValues[1].toIntOrNull() ?: return null
    val b = m.groupValues[2].toIntOrNull() ?: return null
    if (b <= 0) return null
    return a to b
}
