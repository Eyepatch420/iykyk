package com.example.ikyky.features.people.presentation.screen

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.core.ui.components.UriImage
import com.example.ikyky.features.people.domain.model.Appearance
import com.example.ikyky.features.people.presentation.viewmodel.PersonDetailViewModel

/**
 * Grid of one [Person][com.example.ikyky.features.people.domain.model.Person]'s
 * [Appearance]s — the validation surface Phase 7 §8 exists for: inspect
 * grouping quality, representative-frame quality, and spot junk/duplicate/
 * split appearances BEFORE any collage work begins.
 *
 * Each tile currently shows the appearance's timing/quality metadata only —
 * there is no per-appearance crop persisted yet (only one crop per PERSON is
 * produced, by [com.example.ikyky.features.people.domain.usecase.SelectRepresentativeImagesUseCase]);
 * the person's single representative image is shown at the top for reference.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.person?.label ?: "Person") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
        )

        when {
            state.loading -> CircularProgressIndicator(Modifier.padding(24.dp))
            state.error != null -> Text("Error: ${state.error}", Modifier.padding(24.dp))
            else -> Column(Modifier.padding(16.dp)) {
                val cropUri = state.person?.representativeFrame?.presentationCropKey?.let(Uri::parse)
                UriImage(
                    uri = cropUri,
                    contentDescription = state.person?.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)),
                )

                Divider(Modifier.padding(vertical = 12.dp))
                Text(
                    "${state.appearances.size} appearance(s)",
                    fontWeight = FontWeight.Bold,
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    items(state.appearances, key = { it.id }) { appearance ->
                        AppearanceTile(appearance)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceTile(appearance: Appearance, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(appearance.id, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            Text(
                "${appearance.startTimestampMs / 1000.0}s – ${appearance.endTimestampMs / 1000.0}s",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "quality ${"%.2f".format(appearance.bestQuality)} · ${appearance.observationCount} obs",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
