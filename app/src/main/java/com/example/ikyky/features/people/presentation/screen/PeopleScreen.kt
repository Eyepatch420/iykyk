package com.example.ikyky.features.people.presentation.screen

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.core.ui.components.AppScreen
import com.example.ikyky.core.ui.components.AppTopBar
import com.example.ikyky.core.ui.components.LoadingContent
import com.example.ikyky.core.ui.components.MessageState
import com.example.ikyky.core.ui.components.PrimaryButton
import com.example.ikyky.core.ui.components.UriImage
import com.example.ikyky.core.ui.theme.AppShape
import com.example.ikyky.core.ui.theme.Spacing
import com.example.ikyky.features.people.domain.model.Person
import com.example.ikyky.features.people.presentation.viewmodel.PeopleViewModel

/**
 * The people found in the video, shown as a photo collection — a 2-column grid
 * of representative portraits with a minimal caption. No raw IDs, no clustering
 * diagnostics on screen (those stay in logs). Tap a portrait to inspect that
 * person's appearances.
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

    AppScreen(
        modifier = modifier,
        topBar = { AppTopBar(title = "People") },
        horizontalPadding = Spacing.screenHTight,
    ) {
        when {
            state.loading -> LoadingContent("Finding the people in your video")

            state.error != null -> MessageState(
                title = "Something went wrong",
                body = "We couldn't build the list of people. Try again with another video.",
            )

            state.people.isEmpty() -> MessageState(
                title = "No people found",
                body = "Try a video with clearer, closer faces.",
            )

            else -> Column(Modifier.fillMaxSize()) {
                Text(
                    peopleCount(state.uniqueCount),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.md),
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Spacing.sm),
                    modifier = Modifier.weight(1f),
                ) {
                    items(state.people, key = { it.id }) { person ->
                        PersonTile(person = person, onClick = { onOpenPerson(person.id) })
                    }
                }

                PrimaryButton(
                    text = "Make a collage",
                    enabled = state.people.isNotEmpty(),
                    onClick = onMakeCollage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md, bottom = Spacing.md)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}

@Composable
private fun PersonTile(person: Person, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(AppShape.card)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(AppShape.image)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val cropUri = person.representativeFrame?.presentationCropKey?.let(Uri::parse)
            if (cropUri != null) {
                UriImage(
                    uri = cropUri,
                    contentDescription = "A person from the video",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    "No photo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            appearancesLabel(person.appearanceCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.xxs, bottom = Spacing.xs),
        )
    }
}

private fun peopleCount(n: Int) = if (n == 1) "1 person" else "$n people"
private fun appearancesLabel(n: Int) = if (n == 1) "1 appearance" else "$n appearances"
