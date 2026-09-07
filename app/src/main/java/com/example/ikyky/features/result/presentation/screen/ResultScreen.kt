package com.example.ikyky.features.result.presentation.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ikyky.core.di.LocalAppViewModelFactory
import com.example.ikyky.core.ui.components.ActionRow
import com.example.ikyky.core.ui.components.AppScreen
import com.example.ikyky.core.ui.components.LoadingContent
import com.example.ikyky.core.ui.components.MessageState
import com.example.ikyky.core.ui.components.PrimaryButton
import com.example.ikyky.core.ui.components.SecondaryButton
import com.example.ikyky.core.ui.theme.AppShape
import com.example.ikyky.core.ui.theme.Spacing
import com.example.ikyky.features.result.presentation.viewmodel.ResultViewModel

/**
 * The finished creation. The collage fills the screen; a small caption and the
 * two actions — Save (primary) and Share (secondary) — sit below it. No
 * processing details here.
 */
@Composable
fun ResultScreen(
    sessionId: String,
    modifier: Modifier = Modifier,
    viewModel: ResultViewModel = viewModel(factory = LocalAppViewModelFactory.current),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    val fileName = "collage_$sessionId"

    AppScreen(modifier = modifier, horizontalPadding = Spacing.screenHTight) {
        when {
            state.collage == null && state.error == null -> LoadingContent()

            state.error != null && state.collage == null -> MessageState(
                title = "We couldn't load your collage",
                body = "Try creating it again.",
            )

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    "Your collage",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    summary(state.uniquePeople, state.totalAppearances),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xxs),
                )

                Spacer(Modifier.height(Spacing.md))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(AppShape.image)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    state.collage?.let { bmp ->
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = "Your finished collage",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                state.savedUri?.let {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "Saved to your photos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                state.error?.let {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "Couldn't save. Try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(Spacing.md))
                ActionRow(Modifier.fillMaxWidth()) {
                    PrimaryButton(
                        text = if (state.saving) "Saving…" else "Save",
                        enabled = !state.saving && state.collage != null,
                        onClick = { viewModel.onSave(fileName) },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Share",
                        enabled = state.collage != null,
                        onClick = { viewModel.onShare(fileName) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(Spacing.md))
            }
        }
    }
}

private fun summary(people: Int, appearances: Int): String {
    val p = if (people == 1) "1 person" else "$people people"
    val a = if (appearances == 1) "1 appearance" else "$appearances appearances"
    return "$p · $a"
}
