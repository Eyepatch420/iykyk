package com.example.ikyky.features.people.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.features.people.presentation.viewmodel.PeopleViewModel

@Composable
fun PeopleScreen(
    sessionId: String,
    modifier: Modifier = Modifier,
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
                LazyColumn {
                    items(state.people) { p ->
                        Text("${p.label} — ${p.appearanceCount} appearance(s): ${p.appearanceIds.joinToString()}")
                    }
                }
            }
        }
    }
}
