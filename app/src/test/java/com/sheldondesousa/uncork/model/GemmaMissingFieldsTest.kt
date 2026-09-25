package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import org.junit.Assert.assertEquals
import org.junit.Test

class GemmaMissingFieldsTest {
    @Test
    fun findStyleRecommendationsEnvelopeParsesWhenSuppliedFieldsAreOmitted() {
        val card = GemmaConversationResponder.extractSuggestions(
            """{"recommendations":[{"name":"Château Margaux","province":"Bordeaux","variety":"Cabernet Sauvignon"}]}""",
        ).single()

        assertEquals("Château Margaux", card.name)
        assertEquals("Unknown", card.country)
        assertEquals("Bordeaux", card.province)
        assertEquals("Cabernet Sauvignon", card.variety)
    }

    @Test
    fun omittedFieldsBecomeUnknownAndRecordedPreferencesArePreserved() {
        val card = GemmaConversationResponder.extractSuggestions(
            """[WINE_CARDS][{"name":"Château Margaux"}][/WINE_CARDS]""",
        ).single()
        listOf(card.country, card.wineType, card.province, card.variety, card.body,
            card.tannin, card.acidity, card.sweetness, card.preferenceFlavor).forEach {
            assertEquals("Unknown", it)
        }
        val merged = WinePreferences(type = "Red", country = "France").mergeIntoGemmaCard(card)
        assertEquals("Red", merged.wineType)
        assertEquals("France", merged.country)
        assertEquals("Unknown", merged.province)
    }

    @Test
    fun emptyNullAndNoneValuesBecomeUnknownWhileKnownValuesSurvive() {
        val card = GemmaConversationResponder.extractSuggestions(
            """[WINE_CARDS][{"name":"Château Margaux","country":"France","wine_type":"Red","province":null,"variety":" NoNe ","flavor":"","body":"None"}][/WINE_CARDS]""",
        ).single()
        assertEquals("Château Margaux", card.name)
        assertEquals("France", card.country)
        listOf(card.province, card.variety, card.preferenceFlavor, card.body).forEach {
            assertEquals("Unknown", it)
        }
    }

    @Test
    fun generatedDetailsFillOnlyMissingValuesAndCompleteProfile() {
        val original = WineSuggestion(
            name = "Château Margaux",
            country = "France",
            province = "Bordeaux",
            variety = "Cabernet Sauvignon",
            body = "Full-Bodied",
            winery = "Unknown",
        )

        val enriched = GemmaConversationResponder.mergeGeneratedDetails(
            original,
            """[WINE_DETAILS]{"winery":"Château Margaux","body":"Light-Bodied","tannin":"Astringent","flavor_notes":["blackcurrant","cedar"],"summary":"A structured Bordeaux red.","suggested_pairing":"Roast lamb"}[/WINE_DETAILS]""",
        )

        assertEquals("Château Margaux", enriched.winery)
        assertEquals("Full-Bodied", enriched.body)
        assertEquals("Astringent", enriched.tannin)
        assertEquals("blackcurrant, cedar", enriched.flavorNotes)
        assertEquals("A structured Bordeaux red.", enriched.summary)
        assertEquals("Roast lamb", enriched.suggestedPairing)
        assertEquals(true, enriched.profileComplete)
    }
}
