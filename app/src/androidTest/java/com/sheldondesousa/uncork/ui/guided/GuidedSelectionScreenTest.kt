package com.sheldondesousa.uncork.ui.guided

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
        // Every field is single-select: picking a value closes the sheet immediately, no Done button.
        compose.onNodeWithTag("type-tile").performScrollTo().performClick()
        compose.onNodeWithText("Red").performClick()
        compose.onNodeWithText("Submit").assertIsEnabled()
        compose.onNodeWithTag("type-tile").performScrollTo().performClick()
        compose.onNodeWithText("Red").assertIsSelected()
        compose.onNodeWithText("Any type").performClick()
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

    @Test fun pickingANewValueReplacesTheOldOneRatherThanAddingToIt() {
        lateinit var state: GuidedSelectionState
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { GuidedSelectionState(scope, { emptyList() }, { GuidedResult.Complete(emptyList()) }) }
            UncorkTheme { GuidedSelectionScreen(state, {}, {}, {}) }
        }
        compose.onNodeWithTag("tannin-tile").performScrollTo().performClick()
        compose.onNodeWithText("Smooth").performClick()
        compose.runOnIdle { assertEquals(setOf("Smooth"), state.selection.tannin) }
        compose.onNodeWithTag("tannin-tile").performScrollTo().performClick()
        compose.onNodeWithText("Astringent").performClick()
        compose.runOnIdle { assertEquals(setOf("Astringent"), state.selection.tannin) }
    }
}
