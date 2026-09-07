package com.example.ikyky.core.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Fixed component sizes — button heights, icon sizes, minimum touch targets,
 * hero-image ratios. Kept apart from [Spacing] (which is padding/gaps) so a
 * "how tall is a button" question has exactly one answer.
 */
object Dimensions {
    /** Primary / secondary button height. */
    val buttonHeight = 52.dp

    /** Minimum interactive area for any tappable control. */
    val minTouchTarget = 48.dp

    /** Standard UI icon. */
    val icon = 22.dp
    val iconSmall = 18.dp

    /** Compact top app-bar height (Material default is 64; we sit a touch lower). */
    val topBarHeight = 56.dp

    /** Circular progress diameter on calm loading screens. */
    val progressRing = 28.dp

    /** Thickness of the linear progress track on the processing screen. */
    val progressTrack = 4.dp
}
