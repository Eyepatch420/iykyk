package com.example.ikyky.features.collage.presentation.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.features.collage.domain.model.LayoutTemplate
import com.example.ikyky.features.collage.presentation.viewmodel.CollageViewModel

private const val PREVIEW_WIDTH = 1080
private const val PREVIEW_HEIGHT = 1350 // 4:5, a common social-post ratio

/**
 * Generates and previews a collage for the session's people, letting the user
 * switch between every template the data-driven engine offers for that many
 * people. Rendering itself lives entirely in [CollageViewModel] /
 * [com.example.ikyky.features.collage.domain.usecase.GenerateCollageUseCase] —
 * this screen only reflects state and forwards taps.
 */
@Composable
fun CollageScreen(
    sessionId: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollageViewModel = viewModel(factory = LocalAppViewModelFactory.current),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId) { viewModel.load(sessionId, PREVIEW_WIDTH, PREVIEW_HEIGHT) }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text("Collage · ${state.personCount} people", style = MaterialTheme.typography.titleMedium)

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.error != null -> Column(Modifier.padding(top = 16.dp)) {
                Text("Couldn't build a collage: ${state.error?.message}")
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(PREVIEW_WIDTH.toFloat() / PREVIEW_HEIGHT)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val collage = state.collage
                    if (collage != null) {
                        Image(collage.asImageBitmap(), contentDescription = "Collage preview")
                    }
                    if (state.generating) CircularProgressIndicator()
                }

                if (state.availableTemplates.size > 1) {
                    TemplatePicker(
                        templates = state.availableTemplates,
                        selectedId = state.selectedTemplateId,
                        enabled = !state.generating,
                        onSelect = { template ->
                            viewModel.selectTemplate(template.style, PREVIEW_WIDTH, PREVIEW_HEIGHT)
                        },
                    )
                }

                Button(
                    enabled = state.collage != null && !state.generating,
                    onClick = onDone,
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Continue") }
            }
        }
    }
}

@Composable
private fun TemplatePicker(
    templates: List<LayoutTemplate>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (LayoutTemplate) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(templates, key = { it.id }) { template ->
            FilterChip(
                selected = template.id == selectedId,
                enabled = enabled,
                onClick = { onSelect(template) },
                label = { Text(template.style.name.lowercase().replaceFirstChar { it.uppercase() }) },
            )
        }
    }
}
