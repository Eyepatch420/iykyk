package com.example.ikyky.core.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The single spacing scale for the whole app. Screens and components pull
 * padding / gaps from here — never from an inline `.dp` literal — so vertical
 * rhythm stays consistent everywhere.
 */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 40.dp
    val huge = 48.dp

    /** Standard horizontal inset for a content screen. */
    val screenH = 20.dp

    /** Horizontal inset for an image-first screen that wants edge-to-edge media. */
    val screenHTight = 16.dp
}
