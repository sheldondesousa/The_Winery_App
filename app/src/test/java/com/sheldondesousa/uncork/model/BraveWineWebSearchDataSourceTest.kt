package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BraveWineWebSearchDataSourceTest {
    @Test
    fun buildsWineSearchAndSynthesizesUpToRequestedLimit() = runBlocking {
        var capturedQuery = ""
        var capturedResults = emptyList<BraveSearchResult>()
        val source = BraveWineWebSearchDataSource(
            client = BraveSearchClient { query, _ ->
                capturedQuery = query
                listOf(
                    BraveSearchResult("Barolo producer", "https://example.com/barolo", "Italy"),
                )
            },
            synthesizer = WineWebResultSynthesizer { _, results ->
                capturedResults = results
                listOf(
                    WineSuggestion("Web Barolo", "Piedmont", "Italy"),
                    WineSuggestion("Web Barbaresco", "Piedmont", "Italy"),
                    WineSuggestion("Web Nebbiolo", "Lombardy", "Italy"),
                )
            },
        )

        val options = source.search(
            WineWebSearchRequest(
                originalQuery = "A structured Italian red",
                gemmaSuggestions = listOf(
                    WineSuggestion(
                        name = "Barolo",
                        province = "Piedmont",
                        country = "Italy",
                        variety = "Nebbiolo",
                    ),
                ),
                limit = 2,
            ),
        )

        assertTrue(capturedQuery.contains("A structured Italian red"))
        assertTrue(capturedQuery.contains("Barolo"))
        assertTrue(capturedQuery.contains("Nebbiolo"))
        assertEquals(1, capturedResults.size)
        assertEquals(listOf("Web Barolo", "Web Barbaresco"), options.map { it.name })
    }
}
