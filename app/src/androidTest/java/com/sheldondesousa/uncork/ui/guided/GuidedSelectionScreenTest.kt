package com.sheldondesousa.uncork.ui.guided

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.sheldondesousa.uncork.ui.theme.UncorkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GuidedSelectionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anySelectionEnablesSearchAndSheetsPopulateFields() {
        lateinit var state: GuidedSelectionState
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { GuidedSelectionState(scope, { emptyList() }, { GuidedResult.Complete(emptyList()) },
                locations = mapOf("France" to listOf("Bordeaux", "Burgundy"),
                    "Italy" to listOf("Tuscany"))) }
            UncorkTheme { GuidedSelectionScreen(state, {}, {}, {}) }
        }
        compose.onNodeWithText("Submit").assertIsNotEnabled()
        compose.onNodeWithTag("type-tile").performScrollTo().performClick()
        compose.onNodeWithText("Red").performClick().assertIsOn()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Submit").assertIsEnabled()
        compose.onNodeWithTag("type-tile").performScrollTo().performClick()
        compose.onNodeWithText("Red").performClick().assertIsOff()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Submit").assertIsNotEnabled()
        compose.onNodeWithTag("country-tile").performScrollTo().performClick()
        compose.onNodeWithText("Choose country").assertIsDisplayed()
        compose.onNodeWithText("France").performClick()
        compose.onNodeWithTag("country-tile").assertContentDescriptionEquals("Country: France")
        compose.onNodeWithTag("province-tile").performScrollTo().performClick()
        compose.onNodeWithText("Bordeaux").performClick()
        compose.onNodeWithTag("province-tile").assertContentDescriptionEquals("Province: Bordeaux")
        compose.onNodeWithTag("country-tile").performScrollTo().performClick()
        compose.onNodeWithText("Italy").performClick()
        compose.runOnIdle { assertEquals("", state.selection.province) }
        compose.onNodeWithText("Submit").assertIsEnabled().performClick()
        compose.onNodeWithText("Results").assertIsDisplayed()
        compose.onAllNodesWithText("No matches for these selections.").assertCountEquals(2)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Submit").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Italy", state.selection.country) }
    }
    @Test fun threeEqualCellsToggleFromWhitespaceIndicatorAndLabelWithoutDoubleClicks() {
        var selected by mutableStateOf(emptySet<String>())
        var clicks = 0
        compose.setContent {
            UncorkTheme {
                Choices("Type", listOf("Red", "White", "Sparkling", "Rosé", "Fortified"), selected) {
                    clicks++
                    selected = selected.toggled(it)
                }
            }
        }
        val red = compose.onNodeWithTag("type-Red")
        val white = compose.onNodeWithTag("type-White")
        val sparkling = compose.onNodeWithTag("type-Sparkling")
        val redBounds = red.fetchSemanticsNode().boundsInRoot
        val whiteBounds = white.fetchSemanticsNode().boundsInRoot
        val widths = listOf(red, white, sparkling).map { it.fetchSemanticsNode().boundsInRoot.width }
        org.junit.Assert.assertTrue(widths.max() - widths.min() <= 1f)
        red.performTouchInput { click(Offset(redBounds.width - 2f, redBounds.height / 2f)) }
        red.assertIsOn()
        white.performTouchInput { click(Offset(10f, whiteBounds.height / 2f)) }
        white.assertIsOn()
        compose.onNodeWithText("Sparkling").performClick()
        sparkling.assertIsOn()
        red.performTouchInput { click(Offset(redBounds.width - 2f, redBounds.height / 2f)) }
        red.assertIsOff()
        compose.runOnIdle {
            assertEquals(setOf("White", "Sparkling"), selected)
            assertEquals(4, clicks)
        }
    }

}
