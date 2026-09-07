package com.example.ikyky.features.collage.presentation.screen

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.core.ui.components.AppScreen
import com.example.ikyky.core.ui.components.AppTopBar
import com.example.ikyky.core.ui.components.LoadingContent
import com.example.ikyky.core.ui.components.MessageState
import com.example.ikyky.core.ui.components.PrimaryButton
import com.example.ikyky.core.ui.components.SelectChip
import com.example.ikyky.core.ui.theme.AppShape
import com.example.ikyky.core.ui.theme.Dimensions
import com.example.ikyky.core.ui.theme.Spacing
import com.example.ikyky.features.collage.domain.model.LayoutTemplate
import com.example.ikyky.features.collage.presentation.viewmodel.CollageViewModel

private const val PREVIEW_WIDTH = 1080
private const val PREVIEW_HEIGHT = 1350 // 4:5

/**
 * The collage is the screen. A quiet title, the large preview, a compact
 * horizontal template selector below it (chips, never louder than the collage),
 * one primary action. Template switches crossfade. Rendering itself is entirely
 * in [CollageViewModel]; this screen only reflects state.
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

    AppScreen(
        modifier = modifier,
        topBar = { AppTopBar(title = "Your collage") },
        horizontalPadding = Spacing.screenHTight,
    ) {
        when {
            state.loading -> LoadingContent("Laying out your collage")

            state.error != null -> MessageState(
                title = "We couldn't build a collage",
                body = "Something went wrong laying out the people. Try again.",
            )

            else -> Column(Modifier.fillMaxSize()) {
                Text(
                    peopleCount(state.personCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xxs, bottom = Spacing.md),
                )

                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(PREVIEW_WIDTH.toFloat() / PREVIEW_HEIGHT)
                        .clip(AppShape.image)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(
                        targetState = state.collage,
                        animationSpec = tween(220),
                        label = "collage",
                    ) { bmp ->
                        if (bmp != null) {
                            Image(
                                bmp.asImageBitmap(),
                                contentDescription = "Collage preview",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (state.generating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimensions.progressRing),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                if (state.availableTemplates.size > 1) {
                    Text(
                        "Layout",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs),
                    )
                    TemplatePicker(
                        templates = state.availableTemplates,
                        selectedId = state.selectedTemplateId,
                        enabled = !state.generating,
                        onSelect = { template ->
                            viewModel.selectTemplate(template.style, PREVIEW_WIDTH, PREVIEW_HEIGHT)
                        },
                    )
                }

                PrimaryButton(
                    text = "Continue",
                    enabled = state.collage != null && !state.generating,
                    onClick = onDone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(top = Spacing.md, bottom = Spacing.md),
                )
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
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        contentPadding = PaddingValues(vertical = Spacing.xxs),
    ) {
        items(templates, key = { it.id }) { template ->
            SelectChip(
                label = styleLabel(template),
                selected = template.id == selectedId,
                enabled = enabled,
                onClick = { onSelect(template) },
            )
        }
    }
}

private fun styleLabel(t: LayoutTemplate): String =
    t.style.name.lowercase().replaceFirstChar { it.uppercase() }

private fun peopleCount(n: Int) = if (n == 1) "1 person" else "$n people"
