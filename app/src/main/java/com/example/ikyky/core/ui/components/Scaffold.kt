package com.example.ikyky.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.example.ikyky.core.ui.theme.Dimensions
import com.example.ikyky.core.ui.theme.Spacing

/**
 * A compact, flat top bar — no shadow, no oversized headline, tonal only. The
 * title sits at [MaterialTheme.typography.titleLarge]; an optional back affordance
 * and one optional trailing action. Screens that are their own entry point
 * (Video Selection) don't use a bar at all.
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .statusBarsPadding()
                .padding(top = Spacing.sm),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimensions.topBarHeight)
                    .padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(Dimensions.minTouchTarget)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(Dimensions.icon),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    androidx.compose.foundation.layout.Spacer(Modifier.width(Spacing.sm))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (onBack != null) Spacing.xxs else Spacing.xs),
                )
                Box(
                    Modifier.size(Dimensions.minTouchTarget),
                    contentAlignment = Alignment.Center,
                ) { trailing?.invoke() }
            }
        }
    }
}

/**
 * Standard screen container: warm background, optional top bar, content inset by
 * the shared horizontal padding. When there is no [topBar] the content is also
 * pushed below the status bar, so bar-less screens (entry, processing, result)
 * never sit under the clock.
 */
@Composable
fun AppScreen(
    modifier: Modifier = Modifier,
    topBar: @Composable (() -> Unit)? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = Spacing.screenH,
    content: @Composable (PaddingValues) -> Unit,
) {
    androidx.compose.material3.Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { topBar?.invoke() },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .then(if (topBar == null) Modifier.statusBarsPadding() else Modifier)
                .padding(horizontal = horizontalPadding),
        ) {
            content(inner)
        }
    }
}
