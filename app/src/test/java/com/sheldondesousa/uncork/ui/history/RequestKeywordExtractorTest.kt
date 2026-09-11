package com.sheldondesousa.uncork.ui.history

import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestKeywordExtractorTest {
    @Test
    fun extractsUsefulTermsThatWereActuallyRequested() {
        val suggestion = WineSuggestion(
            name = "Catena Malbec",
            winery = "Catena Zapata",
            variety = "Malbec",
            region = "Mendoza, Argentina",
        )

        val keywords = RequestKeywordExtractor.extract(
            request = "Find an Argentinian full-bodied Malbec with high tannins and cherry flavour",
            suggestion = suggestion,
        )

        assertEquals(
            listOf("Argentina", "Full body", "Malbec", "High tannin", "Cherry"),
            keywords,
        )
    }

    @Test
    fun doesNotAddSuggestionFactsMissingFromTheRequest() {
        val suggestion = WineSuggestion(
            name = "Cloudy Bay Sauvignon Blanc",
            variety = "Sauvignon Blanc",
            region = "Marlborough, New Zealand",
            acidity = "High",
        )

        val keywords = RequestKeywordExtractor.extract(
            request = "Suggest a crisp white wine",
            suggestion = suggestion,
        )

        assertEquals(listOf("High acidity", "White"), keywords)
    }
}
