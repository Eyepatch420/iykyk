package com.example.ikyky.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A deliberately small, warm-neutral palette. The photographs supply the colour;
 * the UI stays quiet. One restrained accent (a muted terracotta) marks the
 * single primary action per screen and the active selection state — nothing else.
 *
 * Light and dark are the *same* design, tone-swapped — not two looks.
 */

// --- Light ---------------------------------------------------------------
val WarmWhite = Color(0xFFF8F6F3)      // page background
val Surface = Color(0xFFFFFFFF)        // raised surfaces / images backdrop
val SurfaceMuted = Color(0xFFF1EEEA)   // chips, inert fills, image placeholders
val InkPrimary = Color(0xFF1A1A1A)     // primary text / near-black
val InkSecondary = Color(0xFF6B6660)   // secondary text, metadata
val InkTertiary = Color(0xFF9C978F)    // captions, disabled
val Hairline = Color(0xFFE6E2DC)       // thin tonal separators
val Accent = Color(0xFFB5573B)         // the one accent — muted terracotta
val AccentOnDark = Color(0xFFD9805F)   // accent lifted for dark backgrounds
val OnAccent = Color(0xFFFFFFFF)
val DangerLight = Color(0xFF9B2C2C)

// --- Dark ---------------------------------------------------------------
val WarmBlack = Color(0xFF141312)
val SurfaceDark = Color(0xFF1E1C1A)
val SurfaceMutedDark = Color(0xFF2A2825)
val InkPrimaryDark = Color(0xFFF2EFEA)
val InkSecondaryDark = Color(0xFFA8A29A)
val InkTertiaryDark = Color(0xFF77726B)
val HairlineDark = Color(0xFF322F2C)
val DangerDark = Color(0xFFE07A7A)
