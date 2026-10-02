package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import org.junit.Assert.assertEquals
import org.junit.Test

class GemmaMissingFieldsTest {
    @Test
    fun wineStyleCardUsesVarietyAsItsNavigationTitle() {
        val card = GemmaConversationResponder.extractSuggestions(
            """{"cards":[{"variety":"Sauvignon Blanc","type":"White","country":"New Zealand","region":"Marlborough"}]}""",
        ).single()

        assertEquals("Sauvignon Blanc", card.name)
        assertEquals("white", card.wineType)
        assertEquals("New Zealand", card.country)
        assertEquals("Marlborough", card.province)
        assertEquals("Unknown", card.winery)
        assertEquals(emptyList<String>(), GemmaConversationResponder.missingGemmaCardFields(card))
    }

    @Test
    fun regionAndProvinceResponseKeysShareOneLocation() {
        val cards = GemmaConversationResponder.extractSuggestions(
            """{"cards":[{"variety":"Sangiovese","type":"Red","country":"Italy","province":"Tuscany"},{"variety":"Tempranillo","type":"Red","country":"Spain","province":"Unknown","region":"Rioja"}]}""",
        )

        assertEquals("Tuscany", cards[0].province)
        assertEquals("Rioja", cards[1].province)
        assertEquals(emptyList<String>(), GemmaConversationResponder.missingGemmaCardFields(cards[0]))
        assertEquals(emptyList<String>(), GemmaConversationResponder.missingGemmaCardFields(cards[1]))
    }

    @Test
    fun suppliedCardContractParsesDessertAndRequiresCountryForDisplay() {
        val cards = GemmaConversationResponder.extractSuggestions(
            """{"cards":[{"variety":"Sauternes Blend","type":"Dessert","country":"France","region":"Bordeaux"},{"variety":"Late Harvest Riesling","type":"Dessert","country":"Unknown","region":"Unknown"}]}""",
        )

        assertEquals(2, cards.size)
        assertEquals("sweet", cards[0].wineType)
        assertEquals("Bordeaux", cards[0].province)
        assertEquals("Unknown", cards[1].country)
        assertEquals("Unknown", cards[1].province)
        assertEquals(emptyList<String>(), GemmaConversationResponder.missingGemmaCardFields(cards[0]))
        assertEquals(
            listOf("missing_country"),
            GemmaConversationResponder.missingGemmaCardFields(cards[1]),
        )
    }

    @Test
    fun unknownRegionDoesNotPreventDisplayButOtherMissingFieldsDo() {
        val card = GemmaConversationResponder.extractSuggestions(
            """{"cards":[{"variety":"Malbec","type":"Red","country":"Argentina","region":"Mendoza"}]}""",
        ).single()

        assertEquals(listOf("missing_variety"),
            GemmaConversationResponder.missingGemmaCardFields(card.copy(variety = "Unknown")))
        assertEquals(listOf("missing_wine_type"),
            GemmaConversationResponder.missingGemmaCardFields(card.copy(wineType = "Unknown")))
        assertEquals(listOf("missing_country"),
            GemmaConversationResponder.missingGemmaCardFields(card.copy(country = "Unknown")))
        assertEquals(emptyList<String>(),
            GemmaConversationResponder.missingGemmaCardFields(card.copy(province = "Unknown")))
    }

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

    @Test
    fun wineStyleDetailsStoreTheSixEducatorSections() {
        val original = WineSuggestion(
            name = "Sauvignon Blanc",
            variety = "Sauvignon Blanc",
            wineType = "White",
            country = "New Zealand",
            province = "Marlborough",
            winery = "Unknown",
            requestContext = WinePreferences(type = "White", acidity = "Crisp").toCompactJson(),
        )

        val enriched = GemmaConversationResponder.mergeGeneratedDetails(
            original,
            """
            **Overview:** Sauvignon Blanc is an aromatic white grape variety.
            **Taste:** Light-bodied and crisp, with refreshing acidity.
            **Where it's grown:** Marlborough in New Zealand is especially well known for it.
            **Production facts:** Cool conditions help preserve its fresh aromas.
            **Flavours:** Citrus, gooseberry, passion fruit, and herbs.
            **Best pairings:** Goat cheese, shellfish, salads, and grilled vegetables.
            """.trimIndent(),
        )

        assertEquals("Unknown", enriched.winery)
        assertEquals("Unknown", enriched.body)
        assertEquals("Unknown", enriched.acidity)
        assertEquals(true, enriched.summary.startsWith("**Overview:**"))
        assertEquals(true, enriched.summary.contains("**Best pairings:**"))
        assertEquals(true, enriched.profileComplete)
    }

    @Test
    fun incompleteEducatorFormatDoesNotCompleteTheProfile() {
        val original = WineSuggestion(
            name = "Malbec", variety = "Malbec", wineType = "Red",
            country = "Argentina", province = "Mendoza",
            requestContext = WinePreferences(type = "Red").toCompactJson(),
        )

        val unchanged = GemmaConversationResponder.mergeGeneratedDetails(
            original,
            "**Overview:** Malbec is a red grape.\n**Taste:** Full-bodied.",
        )

        assertEquals("Unknown", unchanged.summary)
        assertEquals(false, unchanged.profileComplete)
    }
}
