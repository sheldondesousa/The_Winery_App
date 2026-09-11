package com.sheldondesousa.uncork.ui.stageshow

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.sheldondesousa.uncork.ui.theme.UncorkTheme
import org.junit.Rule
import org.junit.Test

class StageShowScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsWineDetailsWithoutPersistentNavigation() {
        composeRule.setContent {
            UncorkTheme {
                StageShowRoute(
                    wine = StageWine(
                        ai = WineProfile(
                            winery = "Pinot Noir",
                            variety = "Pinot Noir",
                            region = "Willamette Valley, Oregon",
                        ),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Pinot Noir", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Willamette Valley, Oregon").assertIsDisplayed()
        composeRule.onAllNodesWithText("Unknown", useUnmergedTree = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back to chat").assertIsDisplayed()
        composeRule.onNodeWithText("Chat").assertDoesNotExist()
    }

    @Test
    fun favoriteTagTogglesAndRatingIsReadOnly() {
        composeRule.setContent {
            UncorkTheme {
                StageShowRoute(
                    wine = StageWine(
                        ai = WineProfile(
                            winery = "Pinot Noir",
                            variety = "Pinot Noir",
                            region = "Oregon",
                        ),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Save favorite, off")
            .assertWidthIsEqualTo(88.dp)
            .assertHeightIsEqualTo(88.dp)
            .performClick()
        composeRule.onNodeWithContentDescription("Save favorite, on").assertExists()
        composeRule.onNodeWithText("Saved").assertIsDisplayed()
        composeRule.onNodeWithText("YOU HAVE NOT TRIED THIS WINE").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Save favorite, on").performClick()
        composeRule.onNodeWithContentDescription("Save favorite, off").assertExists()
        composeRule.onNodeWithText("Save").assertIsDisplayed()
    }
}
