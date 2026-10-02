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
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StageShowScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersKnownCardDataWhileMissingDetailsLoad() {
        composeRule.setContent {
            UncorkTheme {
                StageShowRoute(
                    wine = WineSuggestion(
                        name = "Château Margaux",
                        country = "France",
                        province = "Bordeaux",
                        variety = "Cabernet Sauvignon",
                    ).toStageWine(),
                    onBack = {},
                    loadDetails = { _, _ -> awaitCancellation() },
                )
            }
        }

        composeRule.onNodeWithText("Château Margaux").assertIsDisplayed()
        composeRule.onNodeWithText("France").assertIsDisplayed()
        composeRule.onNodeWithText("Bordeaux").assertIsDisplayed()
        composeRule.onNodeWithText("Cabernet Sauvignon").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Loading WINERY").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Loading BODY").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Loading FLAVOR NOTES").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Loading SUMMARY").assertIsDisplayed()
    }

    @Test
    fun showsWineDetailsWithBackAndHomeNavigation() {
        var wentBack = false
        var wentHome = false
        composeRule.setContent {
            UncorkTheme {
                StageShowRoute(
                    wine = StageWine(
                        ai = WineProfile(
                            winery = "Pinot Noir",
                            variety = "Pinot Noir",
                            province = "Willamette Valley, Oregon",
                        ),
                    ),
                    onBack = { wentBack = true },
                    onHome = { wentHome = true },
                )
            }
        }

        composeRule.onNodeWithText("Pinot Noir", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Willamette Valley, Oregon").assertIsDisplayed()
        composeRule.onAllNodesWithText("Unknown", useUnmergedTree = true).onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("RATING").assertDoesNotExist()
        composeRule.onNodeWithText("AI CONFIDENCE · UNKNOWN").assertDoesNotExist()
        composeRule.onNodeWithText("Model estimate, not verified accuracy").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Summary").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertTrue(wentBack)
            assertFalse(wentHome)
        }
        composeRule.onNodeWithContentDescription("Home").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(wentHome) }
    }

    @Test
    fun showsOnlyGemmaSummaryForGemmaWine() {
        composeRule.setContent {
            UncorkTheme {
                StageShowRoute(
                    wine = StageWine(
                        ai = WineProfile(
                            winery = "Unknown",
                            variety = "Nebbiolo",
                            province = "Piedmont",
                            summary = "A structured, aromatic red with firm tannin.",
                        ),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("SUMMARY").assertIsDisplayed()
        composeRule.onNodeWithText("A structured, aromatic red with firm tannin.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Loading details…").assertDoesNotExist()
        composeRule.onNodeWithText("CRITIC REVIEW").assertDoesNotExist()
        composeRule.onNodeWithText("WEB SUMMARY").assertDoesNotExist()
    }

    @Test
    fun showsOnlyCriticReviewForKaggleWine() {
        composeRule.setContent {
            UncorkTheme {
                StageShowRoute(
                    wine = StageWine(
                        ai = WineProfile(
                            winery = "Example Winery",
                            variety = "Nebbiolo",
                            province = "Piedmont",
                            reviewSummary = "The critic's complete review.",
                            source = WineSuggestionSource.KAGGLE,
                        ),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("CRITIC REVIEW").assertIsDisplayed()
        composeRule.onNodeWithText("SUMMARY").assertDoesNotExist()
        composeRule.onNodeWithText("WEB SUMMARY").assertDoesNotExist()
    }

    @Test
    fun showsOnlyWebSummaryForWebSearchWine() {
        composeRule.setContent {
            UncorkTheme {
                StageShowRoute(
                    wine = StageWine(
                        ai = WineProfile(
                            winery = "Online Winery",
                            variety = "Nebbiolo",
                            province = "Piedmont",
                            webSummary = "A concise synthesis of the search findings.",
                            source = WineSuggestionSource.WEB_SEARCH,
                        ),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("WEB SUMMARY").assertIsDisplayed()
        composeRule.onNodeWithText("SUMMARY").assertDoesNotExist()
        composeRule.onNodeWithText("CRITIC REVIEW").assertDoesNotExist()
    }

    @Test
    fun askButtonOpensTheSommelier() {
        var asked = false
        composeRule.setContent {
            UncorkTheme {
                StageShowRoute(
                    wine = StageWine(
                        ai = WineProfile(
                            winery = "Pinot Noir",
                            variety = "Pinot Noir",
                            province = "Oregon",
                        ),
                    ),
                    onBack = {},
                    onAsk = { asked = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Ask AI Sommelier")
            .assertWidthIsEqualTo(88.dp)
            .assertHeightIsEqualTo(88.dp)
            .performClick()
        composeRule.onNodeWithText("Ask").assertIsDisplayed()
        composeRule.onNodeWithText("YOU HAVE NOT TRIED THIS WINE").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(asked) }
    }
}
