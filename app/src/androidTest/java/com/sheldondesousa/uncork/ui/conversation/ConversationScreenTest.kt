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
}
