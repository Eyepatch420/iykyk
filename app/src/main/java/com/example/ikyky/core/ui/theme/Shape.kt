package com.example.ikyky.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Restrained corner radii. Small controls 10dp, cards/images 14dp, major
 * surfaces (sheets) 20dp. Pills (`AppShape.pill`) are reserved for chips and
 * segmented selectors — buttons are NOT pills.
 */
object AppShape {
    val control = RoundedCornerShape(10.dp)
    val card = RoundedCornerShape(14.dp)
    val image = RoundedCornerShape(14.dp)
    val sheet = RoundedCornerShape(20.dp)
    val pill = RoundedCornerShape(999.dp)
}

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = AppShape.control,
    medium = AppShape.card,
    large = AppShape.sheet,
    extraLarge = RoundedCornerShape(28.dp),
)
