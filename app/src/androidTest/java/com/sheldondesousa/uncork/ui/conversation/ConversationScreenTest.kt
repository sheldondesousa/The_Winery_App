package com.sheldondesousa.uncork.ui.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.sheldondesousa.uncork.ui.theme.UncorkTheme
import org.junit.Rule
import org.junit.Test

class ConversationScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sendingMessageAddsUserAndAssistantMessages() {
        val responder = ConversationResponder { query ->
            ChatMessage(
                id = 2L,
                author = MessageAuthor.Assistant,
                text = "A thoughtful match for $query",
            )
        }

        composeRule.setContent {
            UncorkTheme {
                ConversationRoute(responder = responder)
            }
        }

        composeRule.onNodeWithTag("message-input").performTextInput("mushroom risotto")
        composeRule.onNodeWithContentDescription("Send message").performClick()

        composeRule.onNodeWithText("mushroom risotto").assertIsDisplayed()
        composeRule.onNodeWithText("A thoughtful match for mushroom risotto").assertIsDisplayed()
    }

    @Test
    fun responseDisplaysEveryOptionNameWineryAndRating() {
        val responder = ConversationResponder {
            ChatMessage(
                id = 2L,
                author = MessageAuthor.Assistant,
                text = "I found these matches.",
                followUpText = "Would you like to see other options recommended by wine enthusiasts?",
                suggestions = listOf(
                    WineSuggestion(
                        name = "Barolo Riserva",
                        winery = "Marchesi",
                        country = "Italy",
                        province = "Piedmont",
                        variety = "Nebbiolo",
                        rating = 97,
                    ),
                    WineSuggestion(
                        name = "Langhe Nebbiolo",
                        winery = "Unknown",
                        country = "Italy",
                        province = "Tuscany",
                        variety = "Sangiovese",
                    ),
                ),
            )
        }

        composeRule.setContent {
            UncorkTheme {
                ConversationRoute(responder = responder)
            }
        }

        composeRule.onNodeWithTag("message-input").performTextInput("Nebbiolo")
        composeRule.onNodeWithContentDescription("Send message").performClick()

        composeRule.onNodeWithText("Barolo Riserva").assertIsDisplayed()
        composeRule.onNodeWithText("Marchesi").assertIsDisplayed()
        composeRule.onNodeWithText("Italy, Piedmont").assertIsDisplayed()
        composeRule.onNodeWithText("Langhe Nebbiolo").assertIsDisplayed()
        composeRule.onNodeWithText("Italy, Tuscany").assertIsDisplayed()
        composeRule.onNodeWithText("RATING · 97").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Assistant said: I found these matches.")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Assistant said: Would you like to see other options recommended by wine enthusiasts?",
        ).assertIsDisplayed()
    }
}
