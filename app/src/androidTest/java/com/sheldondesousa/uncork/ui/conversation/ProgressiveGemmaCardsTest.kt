package com.sheldondesousa.uncork.ui.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sheldondesousa.uncork.ui.stageshow.StageShowRoute
import com.sheldondesousa.uncork.ui.stageshow.toStageWine
import com.sheldondesousa.uncork.ui.theme.UncorkTheme
import org.junit.Rule
import org.junit.Test

class ProgressiveGemmaCardsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun readyCardOpensWithoutReloadWhileRemainingCardsKeepLoading() {
        val first = WineSuggestion(
            name = "First wine", province = "Bordeaux", country = "France",
            summary = "Unknown", flavorNotes = "Blackcurrant, cedar", profileComplete = true,
        )
        val results = mutableStateOf(SourceResult(WineSuggestionSource.GEMMA, SourceQueryStatus.LOADING, listOf(first)))
        compose.setContent {
            UncorkTheme {
                var selected by remember { mutableStateOf<WineSuggestion?>(null) }
                SourceResultCard(results.value, onSuggestionClick = { selected = it })
                selected?.let { wine ->
                    StageShowRoute(wine = wine.toStageWine(), onBack = { selected = null })
                }
            }
        }
        compose.onNodeWithText("Preparing wine 2…").assertIsDisplayed()
        compose.onNodeWithText("Preparing wine 3…").assertIsDisplayed()
        compose.onNodeWithText("First wine").performClick()
        compose.runOnIdle {
            results.value = results.value.copy(suggestions = listOf(first, first.copy(name = "Second wine")))
        }
        compose.onNodeWithText("Loading details…").assertDoesNotExist()
    }
}
