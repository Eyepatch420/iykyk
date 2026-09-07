package com.example.ikyky.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ikyky.core.ui.theme.AppShape
import com.example.ikyky.core.ui.theme.Dimensions
import com.example.ikyky.core.ui.theme.Spacing

/**
 * The three button weights, used app-wide. There is ONE [PrimaryButton] per
 * screen; everything else is [SecondaryButton] or [TertiaryButton]. All share
 * the same height, the same 10dp radius (deliberately not a pill), and the
 * same label style. No elevation — surfaces are flat.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = AppShape.control,
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        modifier = modifier.heightIn(min = Dimensions.buttonHeight),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = AppShape.control,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.heightIn(min = Dimensions.buttonHeight),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = AppShape.control,
        modifier = modifier.heightIn(min = Dimensions.minTouchTarget),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
}

/** A horizontal group of actions with the standard gap. */
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
