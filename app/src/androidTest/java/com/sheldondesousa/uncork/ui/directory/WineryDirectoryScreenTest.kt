package com.sheldondesousa.uncork.ui.directory

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sheldondesousa.uncork.data.knowledge.WineriesDirectory
import com.sheldondesousa.uncork.data.knowledge.WineryLocation
import com.sheldondesousa.uncork.ui.theme.UncorkTheme
import org.junit.Rule
import org.junit.Test

class WineryDirectoryScreenTest {
    @get:Rule val compose = createComposeRule()

    private val directory = WineriesDirectory(
        listOf(
            WineryLocation("Zeta Winery", "France", "Bordeaux"),
            WineryLocation("Alpha Winery", "France", "Bordeaux"),
            WineryLocation("Beta Winery", "France", "Alsace"),
            WineryLocation("Gamma Winery", "Chile", "Maipo"),
        ),
    )

    @Test fun drillsFromCountryToRegionToWineriesAndBackUpOneLevelAtATime() {
        var left = false
        compose.setContent {
            UncorkTheme { WineryDirectoryRoute(loadDirectory = { directory }, onBack = { left = true }, onHome = {}) }
        }
        compose.onNodeWithText("Directory").assertIsDisplayed()
        compose.onNodeWithText("Select Country").assertIsDisplayed()
        compose.onNodeWithTag("directory-country-France").performClick()
        compose.onNodeWithText("France > Select Region").assertIsDisplayed()
        compose.onNodeWithTag("directory-region-Bordeaux").performClick()
        compose.onNodeWithText("France > Bordeaux > Winery").assertIsDisplayed()
        compose.onNodeWithText("Alpha Winery").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("France > Select Region").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Select Country").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle { assert(left) }
    }
}
