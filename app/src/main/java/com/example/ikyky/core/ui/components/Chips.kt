package com.example.ikyky.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.ikyky.core.ui.theme.AppShape
import com.example.ikyky.core.ui.theme.Spacing

/**
 * Compact selectable chip for a horizontal selector (e.g. collage templates).
 * Pill-shaped (chips are one of the few places a pill is right), quiet when
 * unselected, a subtle filled accent when selected — never louder than the
 * content it controls.
 */
@Composable
fun SelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "chipBg",
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "chipFg",
    )
    Box(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(AppShape.pill)
            .background(bg)
            .then(
                if (selected) Modifier
                else Modifier.border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    AppShape.pill,
                )
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}
