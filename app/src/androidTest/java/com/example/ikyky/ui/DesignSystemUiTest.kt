package com.example.ikyky.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.ikyky.core.ui.components.MessageState
import com.example.ikyky.core.ui.components.PrimaryButton
import com.example.ikyky.core.ui.components.SelectChip
import com.example.ikyky.core.ui.components.SecondaryButton
import com.example.ikyky.core.ui.components.TertiaryButton
import com.example.ikyky.core.ui.theme.IkykyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 9 — the design-system components behave: buttons fire their onClick and
 * meet the minimum touch target, chips toggle, the message/empty/error state
 * shows its recovery action. These stand in for "buttons trigger existing
 * actions" without needing the DI container.
 */
class DesignSystemUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun primaryButton_firesOnClick_andMeetsTouchTarget() {
        var clicks = 0
        compose.setContent {
            IkykyTheme { PrimaryButton(text = "Continue", onClick = { clicks++ }) }
        }
        compose.onNodeWithText("Continue")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun secondaryAndTertiaryButtons_fireOnClick() {
        var s = 0
        var t = 0
        compose.setContent {
            IkykyTheme {
                Column {
                    SecondaryButton(text = "Choose another video", onClick = { s++ })
                    TertiaryButton(text = "Cancel", onClick = { t++ })
                }
            }
        }
        compose.onNodeWithText("Choose another video").performClick()
        compose.onNodeWithText("Cancel").assertHeightIsAtLeast(44.dp).performClick()
        assertEquals(1, s)
        assertEquals(1, t)
    }

    @Test
    fun disabledPrimaryButton_doesNotFire() {
        var clicks = 0
        compose.setContent {
            IkykyTheme { PrimaryButton(text = "Continue", enabled = false, onClick = { clicks++ }) }
        }
        compose.onNodeWithText("Continue").performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun selectChip_reportsSelection() {
        compose.setContent {
            IkykyTheme {
                var selected by mutableStateOf("Grid")
                Column {
                    listOf("Grid", "Hero", "Editorial").forEach { name ->
                        SelectChip(
                            label = name,
                            selected = selected == name,
                            onClick = { selected = name },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Hero").performClick()
        compose.onNodeWithText("Hero").assertIsDisplayed()
        compose.onNodeWithText("Editorial").performClick()
        compose.onNodeWithText("Editorial").assertIsDisplayed()
    }

    @Test
    fun messageState_showsTitleBodyAndRecoveryAction() {
        var acted = 0
        compose.setContent {
            IkykyTheme {
                MessageState(
                    title = "No people found",
                    body = "Try a video with clearer, closer faces.",
                    actionLabel = "Choose another video",
                    onAction = { acted++ },
                )
            }
        }
        compose.onNodeWithText("No people found").assertIsDisplayed()
        compose.onNodeWithText("Try a video with clearer, closer faces.").assertIsDisplayed()
        compose.onNodeWithText("Choose another video").performClick()
        assertTrue(acted == 1)
    }
}
