package com.sheldondesousa.uncork.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.theme.UncorkTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyHistoryInvitesAConversation() {
        var chatSelected = false

        composeRule.setContent {
            UncorkTheme {
                HistoryRoute(
                    entries = emptyList(),
                    onEntryClick = {},
                    onTabSelected = { chatSelected = true },
                )
            }
        }

        composeRule.onNodeWithText("Your wine suggestions will appear here.").assertIsDisplayed()
        composeRule.onNodeWithText("Start a conversation").performClick()
        assertTrue(chatSelected)
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
            excerpt = "Dark fruit and gentle spice make this a generous pairing.",
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
        composeRule.onNodeWithContentDescription("Open Catena Malbec details").performClick()
        assertTrue(opened)
    }
}
