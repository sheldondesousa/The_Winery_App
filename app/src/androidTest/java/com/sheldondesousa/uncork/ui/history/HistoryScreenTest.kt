package com.sheldondesousa.uncork.ui.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.theme.UncorkTheme
import org.junit.Rule
import org.junit.Test

class HistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyHistoryLeavesTheContentAreaBlank() {
        composeRule.setContent {
            UncorkTheme {
                HistoryRoute(
                    entries = emptyList(),
                    onEntryClick = {},
                    onTabSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("History").assertIsDisplayed()
        composeRule.onAllNodesWithText("Your wine suggestions will appear here.").assertCountEquals(0)
    }

    @Test
    fun historyEntryOpensWineDetails() {
        var opened = false
        val entry = HistoryEntry(
            id = 1L,
            createdAtEpochMillis = System.currentTimeMillis(),
            suggestion = WineSuggestion(
                name = "Catena Malbec",
                region = "Mendoza, Argentina",
                variety = "Malbec",
            ),
            request = "Suggest a Malbec for steak",
        )

        composeRule.setContent {
            UncorkTheme {
                HistoryRoute(
                    entries = listOf(entry),
                    onEntryClick = { opened = true },
                    onTabSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("TODAY").assertIsDisplayed()
        composeRule.onNodeWithText("Catena Malbec").assertIsDisplayed()
        composeRule.onNodeWithText("Mendoza, Argentina").assertIsDisplayed()
        composeRule.onNodeWithText("Request: Suggest a Malbec for steak").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Catena Malbec details").performClick()
        org.junit.Assert.assertTrue(opened)
    }

    @Test
    fun selectedHistoryEntryCanBeDeleted() {
        var deletedIds = emptySet<Long>()
        val entry = HistoryEntry(
            id = 42L,
            createdAtEpochMillis = System.currentTimeMillis(),
            suggestion = WineSuggestion(name = "Barolo", region = "Piedmont, Italy"),
            request = "Barolo for braised beef",
        )

        composeRule.setContent {
            UncorkTheme {
                HistoryRoute(
                    entries = listOf(entry),
                    onEntryClick = {},
                    onDeleteEntries = { deletedIds = it },
                    onTabSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("Delete").assertIsNotEnabled()
        composeRule.onNodeWithText("Select").performClick()
        composeRule.onNodeWithContentDescription("Select Barolo").performClick()
        composeRule.onNodeWithText("Delete").assertIsEnabled().performClick()

        org.junit.Assert.assertEquals(setOf(42L), deletedIds)
    }
}
