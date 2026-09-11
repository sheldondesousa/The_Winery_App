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
            requestKeywords = listOf("Malbec"),
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
        composeRule.onNodeWithText("Your Request: Malbec").assertIsDisplayed()
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
            requestKeywords = listOf("Barolo"),
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

        composeRule.onNodeWithText("Clear").performClick()
        composeRule.onNodeWithText("Delete").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Select Barolo").performClick()
        composeRule.onNodeWithText("Delete").assertIsEnabled().performClick()

        org.junit.Assert.assertEquals(setOf(42L), deletedIds)
    }

    @Test
    fun clearAllSelectsEveryHistoryEntryForDeletion() {
        var deletedIds = emptySet<Long>()
        val entries = listOf(1L, 2L).map { id ->
            HistoryEntry(
                id = id,
                createdAtEpochMillis = System.currentTimeMillis(),
                suggestion = WineSuggestion(name = "Wine $id", region = "Region"),
                requestKeywords = listOf("Wine"),
            )
        }

        composeRule.setContent {
            UncorkTheme {
                HistoryRoute(
                    entries = entries,
                    onEntryClick = {},
                    onDeleteEntries = { deletedIds = it },
                    onTabSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("Clear All").performClick()
        composeRule.onNodeWithText("Delete").assertIsEnabled().performClick()

        org.junit.Assert.assertEquals(setOf(1L, 2L), deletedIds)
    }
}
