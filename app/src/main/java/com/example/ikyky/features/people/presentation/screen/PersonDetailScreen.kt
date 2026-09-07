package com.example.ikyky.features.people.presentation.screen

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.core.ui.components.AppScreen
import com.example.ikyky.core.ui.components.AppTopBar
import com.example.ikyky.core.ui.components.LoadingContent
import com.example.ikyky.core.ui.components.MessageState
import com.example.ikyky.core.ui.components.UriImage
import com.example.ikyky.core.ui.theme.AppShape
import com.example.ikyky.core.ui.theme.Spacing
import com.example.ikyky.features.people.domain.model.Appearance
import com.example.ikyky.features.people.presentation.viewmodel.PersonDetailViewModel

/**
 * One person's page: a large hero portrait, a plain caption, then each
 * appearance as a quiet timeline row (where in the video it occurs, and how
 * long). No per-appearance crop exists in the domain, so the rows stay visual
 * without inventing data; timing is shown in seconds, not ML terminology.
 */
@Composable
fun PersonDetailScreen(
    sessionId: String,
    personId: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: PersonDetailViewModel = viewModel(factory = LocalAppViewModelFactory.current),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId, personId) { viewModel.load(sessionId, personId) }

    AppScreen(
        modifier = modifier,
        topBar = { AppTopBar(title = "Person", onBack = onBack) },
        horizontalPadding = Spacing.screenHTight,
    ) {
        when {
            state.loading -> LoadingContent()
            state.error != null -> MessageState(
                title = "We couldn't open this person",
                body = "Go back and pick another.",
                actionLabel = "Back",
                onAction = onBack,
            )

            else -> {
                val appearances = state.appearances
                val spanMs = appearances.maxOfOrNull { it.endTimestampMs } ?: 1L
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = Spacing.xs, bottom = Spacing.xxl,
                    ),
                ) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(AppShape.image)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            val cropUri = state.person?.representativeFrame
                                ?.presentationCropKey?.let(Uri::parse)
                            if (cropUri != null) {
                                UriImage(
                                    uri = cropUri,
                                    contentDescription = "This person",
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.md))
                        Text(
                            appearancesLabel(appearances.size),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Where this person shows up in the video",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.md))
                    }
                    items(appearances, key = { it.id }) { appearance ->
                        AppearanceRow(appearance = appearance, totalMs = spanMs)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceRow(appearance: Appearance, totalMs: Long, modifier: Modifier = Modifier) {
    val startFrac = (appearance.startTimestampMs.toFloat() / totalMs).coerceIn(0f, 1f)
    val widthFrac = ((appearance.endTimestampMs - appearance.startTimestampMs)
        .toFloat() / totalMs).coerceIn(0.02f, 1f)

    Column(modifier.padding(vertical = Spacing.sm)) {
        Text(
            timeRange(appearance.startTimestampMs, appearance.endTimestampMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        // A thin track with a filled segment marking this appearance's span.
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(AppShape.pill)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(widthFrac)
                    .height(4.dp)
                    .clip(AppShape.pill)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
                content = {},
            )
        }
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            durationLabel(appearance.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun timeRange(startMs: Long, endMs: Long): String =
    "%.1fs – %.1fs".format(startMs / 1000.0, endMs / 1000.0)

private fun durationLabel(ms: Long): String {
    val s = ms / 1000.0
    return if (s < 1.0) "under a second" else "%.1f seconds on screen".format(s)
}

private fun appearancesLabel(n: Int) = if (n == 1) "1 appearance" else "$n appearances"
