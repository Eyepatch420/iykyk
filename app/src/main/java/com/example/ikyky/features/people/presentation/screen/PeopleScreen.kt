package com.example.ikyky.features.people.presentation.screen

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.core.ui.components.UriImage
import com.example.ikyky.features.people.domain.model.Person
import com.example.ikyky.features.people.presentation.viewmodel.PeopleViewModel

/**
 * "We found N people" — one card per [Person] with their representative image
 * and appearance count. Tap a card to inspect that person's appearances
 * ([com.example.ikyky.features.people.presentation.screen.PersonDetailScreen]),
 * which exists specifically to visually validate grouping before any collage
 * work begins.
 *
 * Deliberately NOT collage-styled — plain cards, no template/layout
 * previewing.
 */
@Composable
fun PeopleScreen(
    sessionId: String,
    modifier: Modifier = Modifier,
    onOpenPerson: (personId: String) -> Unit = {},
    onMakeCollage: () -> Unit = {},
    viewModel: PeopleViewModel = viewModel(factory = LocalAppViewModelFactory.current),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        when {
            state.loading -> {
                CircularProgressIndicator()
                Text("Building identities…")
            }

            state.error != null -> Text("Error: ${state.error}")

            else -> {
                Text("Unique people: ${state.uniqueCount}", fontWeight = FontWeight.Bold)
                Text("Total appearances: ${state.totalAppearances}")

                state.diagnostics?.let { d ->
                    Divider(Modifier.padding(vertical = 8.dp))
                    Text("Appearances in: ${d.inputAppearances} → after split: ${d.appearancesAfterSplit} (+${d.splitsApplied})")
                    d.calibration?.let { c ->
                        val conf = if (c.fallbackUsed) "fallback" else "%.2f".format(c.confidence)
                        Text("Merge threshold: ${"%.3f".format(c.threshold)} ($conf${if (c.lowConfidence) ", low-confidence" else ""})")
                    }
                    Text("Must-not-link edges: ${d.mustNotLinkEdges.size} · merges blocked: ${d.mergesBlockedByMustNotLink}")
                }

                Divider(Modifier.padding(vertical = 8.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.people, key = { it.id }) { person ->
                        PersonCard(person = person, onClick = { onOpenPerson(person.id) })
                    }
                }

                Button(
                    onClick = onMakeCollage,
                    enabled = state.people.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Make collage") }
            }
        }
    }
}

@Composable
private fun PersonCard(person: Person, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val cropUri = person.representativeFrame?.presentationCropKey?.let(Uri::parse)
            UriImage(
                uri = cropUri,
                contentDescription = person.label,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column {
                Text(person.label, fontWeight = FontWeight.Bold)
                Text(
                    "${person.appearanceCount} appearance(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
