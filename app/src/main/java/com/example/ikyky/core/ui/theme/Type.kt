package com.example.ikyky.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * One type scale, mapped onto Material 3's `Typography` slots so existing
 * `MaterialTheme.typography.*` call sites keep working. Uses the platform
 * system font (no downloaded fonts). Weights are restrained — SemiBold for
 * titles and buttons, Normal for body — nothing heavier, and no all-caps.
 */
private val System = FontFamily.Default

private val tightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val AppTypography = Typography(
    // Hero / display — the one big line on an entry screen.
    displaySmall = TextStyle(
        fontFamily = System, fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.4).sp,
        lineHeightStyle = tightLineHeight,
    ),
    // Screen title (top bar / first line of a screen).
    headlineSmall = TextStyle(
        fontFamily = System, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = System, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp,
    ),
    // Section title.
    titleMedium = TextStyle(
        fontFamily = System, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = System, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
    ),
    // Body.
    bodyLarge = TextStyle(
        fontFamily = System, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = System, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp,
    ),
    // Secondary / metadata.
    bodySmall = TextStyle(
        fontFamily = System, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp,
    ),
    // Button.
    labelLarge = TextStyle(
        fontFamily = System, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = System, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp,
    ),
    // Caption.
    labelSmall = TextStyle(
        fontFamily = System, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp,
    ),
)
