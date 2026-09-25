package com.sheldondesousa.uncork.model

import androidx.test.core.app.ApplicationProvider
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GemmaConversationResponderTest {
    @Test
    fun contextCapacityErrorIsRecognizedThroughWrappedCause() {
        val error = IllegalStateException(
            "send failed",
            IllegalArgumentException(
                "FAILED_PRECONDITION: Prefill input length exceeds available state entries " +
                    "(remaining capacity: 224).",
            ),
        )

        assertEquals(
            true,
            with(GemmaConversationResponder) { error.isContextCapacityError() },
        )
        assertEquals(
            false,
            with(GemmaConversationResponder) {
                IllegalStateException("another failure").isContextCapacityError()
            },
        )
    }

    @Test
    fun winePreferencesRoundTripAndEnforceResolvedType() {
        val preferences = WinePreferences(
            type = "red",
            country = "France",
            body = "full",
        )

        val parsed = WinePreferences.fromJsonOrNull(preferences.toCompactJson())

        assertEquals(preferences, parsed)
        assertEquals(
            true,
            parsed?.acceptsCard(
                cardType = "red",
                cardCountry = "France",
                cardBody = "full",
                cardTannin = "medium",
                cardAcidity = "high",
                cardVariety = "Cabernet Sauvignon",
                cardFlavor = "dark fruit",
                cardOccasion = "dinner",
            ),
        )
        assertEquals(
            false,
            parsed?.acceptsCard(
                cardType = "white",
                cardCountry = "France",
                cardBody = "full",
                cardTannin = "Unknown",
                cardAcidity = "Unknown",
                cardVariety = "Unknown",
                cardFlavor = "Unknown",
                cardOccasion = "Unknown",
            ),
        )
        assertEquals(
            false,
            parsed?.acceptsCard(
                cardType = "red",
                cardCountry = "Italy",
                cardBody = "full",
                cardTannin = "Unknown",
                cardAcidity = "Unknown",
                cardVariety = "Unknown",
                cardFlavor = "Unknown",
                cardOccasion = "Unknown",
            ),
        )
        assertNull(WinePreferences.fromJsonOrNull("not-json"))
    }

    @Test
    fun resolvedPreferencesMustMatchWhileUnknownPreferencesRemainUnrestricted() {
        val preferences = WinePreferences(
            type = "rosé",
            country = "France",
            body = "light",
            tannin = "Unknown",
            acidity = "high",
            variety = "Grenache",
            flavor = "strawberry",
            occasion = "summer lunch",
        )

        assertEquals(
            true,
            preferences.acceptsCard(
                cardType = "rose",
                cardCountry = "france",
                cardBody = "light",
                cardTannin = "low",
                cardAcidity = "high",
                cardVariety = "grenache",
                cardFlavor = "strawberry",
                cardOccasion = "summer lunch",
            ),
        )
        assertEquals(
            listOf("variety expected 'Grenache' but was 'Cinsault'"),
            preferences.cardMismatchReasons(
                cardType = "rose",
                cardCountry = "France",
                cardBody = "light",
                cardTannin = "high",
                cardAcidity = "high",
                cardVariety = "Cinsault",
                cardFlavor = "strawberry",
                cardOccasion = "summer lunch",
            ),
        )
    }

    @Test
    fun webResultPayloadParsesAsWebOptionsWithoutCriticData() {
        val response = """
            [WEB_RESULTS]
            [
              {"name":"Example Barolo","winery":"Example Winery","country":"Italy","province":"Piedmont","variety":"Nebbiolo","body":"Full-Bodied","tannin":"Astringent","acidity":"Tart","flavor_notes":["rose","tar"],"suggested_pairing":"Unknown","web_summary":"A structured Nebbiolo supported by the supplied search results."}
            ]
            [/WEB_RESULTS]
        """.trimIndent()

        val suggestion = GemmaConversationResponder.extractWebSuggestions(response).single()

        assertEquals(WineSuggestionSource.WEB_SEARCH, suggestion.source)
        assertEquals("Example Barolo", suggestion.name)
        assertEquals("Italy", suggestion.country)
        assertEquals("rose, tar", suggestion.flavorNotes)
        assertEquals("A structured Nebbiolo supported by the supplied search results.", suggestion.webSummary)
        assertEquals("Unknown", suggestion.summary)
        assertEquals("Unknown", suggestion.reviewSummary)
        assertNull(suggestion.rating)
    }

    @Test
    fun braveResponseParsesWebResults() {
        val response = """
            {"web":{"results":[
              {"title":"Wine result","url":"https://example.com/wine","description":"A useful excerpt.","extra_snippets":["A named bottle from Italy."]},
              {"title":"Incomplete result","url":"","description":"Ignored."}
            ]}}
        """.trimIndent()

        val results = BraveSearchHttpClient("test-key").parseResults(response)

        assertEquals(1, results.size)
        assertEquals("Wine result", results.single().title)
        assertEquals(
            "A useful excerpt. A named bottle from Italy.",
            results.single().description,
        )
    }

    @Test
    fun stageOneCardArrayParsesThreeOptionsWithImmediateSummaries() {
        val response = """
            These three Italian reds should give you a useful range.
            [WINE_CARDS]
            [
              {"name":"Barolo","wine_type":"red","variety":"Nebbiolo","country":"Italy","province":"Piedmont","flavor":"rose","occasion":"dinner","summary":"A structured Nebbiolo with floral aromas and firm tannin."},
              {"name":"Brunello di Montalcino","wine_type":"red","variety":"Sangiovese","country":"Italy","province":"Tuscany","summary":"A full-bodied Tuscan red with savory cherry character."},
              {"name":"Etna Rosso","wine_type":"red","variety":"Nerello Mascalese","country":"Italy","province":"Sicily","summary":"An elegant volcanic red with bright fruit and mineral notes."}
            ]
            [/WINE_CARDS]
        """.trimIndent()

        val suggestions = GemmaConversationResponder.extractSuggestions(response)

        assertEquals(3, suggestions.size)
        assertEquals(listOf("Barolo", "Brunello di Montalcino", "Etna Rosso"), suggestions.map { it.name })
        assertEquals(listOf("red", "red", "red"), suggestions.map { it.wineType })
        assertEquals(listOf("Unknown", "Unknown", "Unknown"), suggestions.map { it.winery })
        assertEquals("rose", suggestions.first().preferenceFlavor)
        assertEquals("dinner", suggestions.first().occasion)
        assertEquals(
            "A structured Nebbiolo with floral aromas and firm tannin.",
            suggestions.first().summary,
        )
        assertEquals(true, suggestions.all { it.summary.length < 200 })
    }

    @Test
    fun profileWithoutRatingOrConfidenceParsesWithNullRating() {
        val longSummary = "A".repeat(240)
        val response = """
            A Barolo would pair well with the dish.
            [WINE_PROFILE]
            {
              "name": "Barolo",
              "winery": "Marchesi di Barolo",
              "country": "Italy",
              "variety": "Nebbiolo",
              "province": "Piedmont",
              "body": "Full-Bodied",
              "tannin": "Astringent",
              "acidity": "Tart",
              "flavor_notes": "cherry, rose, tar",
              "suggested_pairing": "Braised beef",
              "summary": "$longSummary",
              "review_summary": "Model-generated critic text must be ignored",
              "web_summary": "Model-generated web text must be ignored"
            }
            [/WINE_PROFILE]
        """.trimIndent()

        val suggestion = GemmaConversationResponder.extractSuggestions(response).firstOrNull()

        assertNotNull(suggestion)
        assertEquals("Barolo", suggestion?.name)
        assertEquals("Nebbiolo", suggestion?.variety)
        assertEquals("Italy", suggestion?.country)
        assertEquals("Piedmont", suggestion?.province)
        assertEquals("Braised beef", suggestion?.suggestedPairing)
        assertEquals(WineSuggestionSource.GEMMA, suggestion?.source)
        // 220 = 200 chars + a 10% buffer, since a model asked for "under 200 characters" often
        // overruns slightly — truncating harder than that buffer just cuts a word off for no
        // reason.
        assertEquals(220, suggestion?.summary?.length)
        assertEquals("Unknown", suggestion?.reviewSummary)
        assertEquals("Unknown", suggestion?.webSummary)
        assertNull(suggestion?.rating)
    }

    @Test
    fun twoProfileMarkersParseAsTwoDistinctGemmaOptions() {
        val response = """
            I would try these two wines.
            [WINE_PROFILE]
            {"name":"Barolo","winery":"Unknown","country":"Italy","province":"Piedmont","variety":"Nebbiolo","body":"Full-Bodied","tannin":"Astringent","acidity":"Tart","flavor_notes":"rose, tar","suggested_pairing":"Lamb"}
            [/WINE_PROFILE]
            [WINE_PROFILE]
            {"name":"Brunello","winery":"Unknown","country":"Italy","province":"Tuscany","variety":"Sangiovese","body":"Full-Bodied","tannin":"Moderate","acidity":"Tart","flavor_notes":"cherry, herbs","suggested_pairing":"Lamb"}
            [/WINE_PROFILE]
        """.trimIndent()

        val suggestions = GemmaConversationResponder.extractSuggestions(response)

        assertEquals(listOf("Barolo", "Brunello"), suggestions.map { it.name })
        assertEquals(listOf("Piedmont", "Tuscany"), suggestions.map { it.province })
    }

    @Test
    fun cardParsesWithoutOptionalWineryProvinceOrVariety() {
        val response = """
            [WINE_PROFILE]
            {"name":"A dependable red blend","country":"France"}
            [/WINE_PROFILE]
        """.trimIndent()

        val suggestion = GemmaConversationResponder.extractSuggestions(response).firstOrNull()

        assertNotNull(suggestion)
        assertEquals("A dependable red blend", suggestion?.name)
        assertEquals("France", suggestion?.country)
        assertEquals("Unknown", suggestion?.province)
        assertEquals("Unknown", suggestion?.winery)
        assertEquals("Unknown", suggestion?.variety)
    }

    @Test
    fun profileFlavorNotesArrayParsesToDisplayValue() {
        val response = """
            [WINE_PROFILE]
            {"name":"Barolo","country":"Italy","province":"Piedmont","variety":"Nebbiolo","flavor_notes":["red cherry","rose petal","tar"]}
            [/WINE_PROFILE]
        """.trimIndent()

        val suggestion = GemmaConversationResponder.extractSuggestions(response).firstOrNull()

        assertEquals("red cherry, rose petal, tar", suggestion?.flavorNotes)
    }

    @Test
    fun rawCardArrayIsParsedFromUnmarkedJson() {
        val response = """
            These are three crisp options to explore.
            [
              {"name":"Chablis","wine_type":"white","variety":"Chardonnay","country":"France","province":"Burgundy"},
              {"name":"Sancerre","wine_type":"white","variety":"Sauvignon Blanc","country":"France","province":"Loire"},
              {"name":"Soave Classico","wine_type":"white","variety":"Garganega","country":"Italy","province":"Veneto"}
            ]
        """.trimIndent()

        val suggestions = GemmaConversationResponder.extractSuggestions(response)

        assertEquals(3, suggestions.size)
        assertEquals(listOf("Chablis", "Sancerre", "Soave Classico"), suggestions.map { it.name })
    }

    @Test
    fun matchModeChoiceRecognizesButtonLabelsAndFreeText() {
        assertEquals(ModeChoice.Curious, matchModeChoice("Curious about wines and pairings"))
        assertEquals(ModeChoice.FindWine, matchModeChoice("Find a wine"))
        assertEquals(ModeChoice.FindWine, matchModeChoice("I'd like you to recommend something"))
        assertEquals(ModeChoice.Curious, matchModeChoice("I have a question about pairings"))
        assertNull(matchModeChoice("res"))
    }

    @Test
    fun matchWineTypeResolvesSynonymsAndRejectsAmbiguity() {
        assertEquals("red", matchWineType("I'd like a red please"))
        assertEquals("rose", matchWineType("something pink"))
        assertEquals("sparkling", matchWineType("bubbly for a toast"))
        assertNull(matchWineType("res"))
    }

    @Test
    fun matchLocationFindsCountryAndInfersFromProvince() {
        assertEquals(LocationMatch("France", "Bordeaux"), matchLocation("something from Bordeaux"))
        assertEquals(LocationMatch("Italy", "Unknown"), matchLocation("Italian please"))
        assertNull(matchLocation("somewhere nice"))
    }

    @Test
    fun matchTasteRecognizesFindTabVocabularyNotLowMediumHigh() {
        val match = matchTaste("light and crisp")
        assertEquals("Light-Bodied", match.body)
        assertEquals("Crisp", match.acidity)
        assertEquals(true, matchTaste("low").isEmpty)
    }

    @Test
    fun matchTasteRecognizesSommelierSynonymVocabulary() {
        val velvety = matchTaste("velvety")
        assertEquals("Full-Bodied", velvety.body)
        assertEquals("Smooth", velvety.tannin)

        val grippy = matchTaste("quite grippy on the finish")
        assertEquals("Moderate", grippy.tannin)

        val zingy = matchTaste("zingy and refreshing")
        assertEquals("Tart", zingy.acidity)

        val honeyed = matchTaste("honeyed and rich")
        assertEquals("Sweet", honeyed.sweetness)
        assertEquals("Full-Bodied", honeyed.body)
    }

    @Test
    fun isNoPreferenceAcceptsCommonPhrasing() {
        assertEquals(true, isNoPreference("no preference"))
        assertEquals(true, isNoPreference("surprise me"))
        assertEquals(false, isNoPreference("red"))
    }

    @Test
    fun isLikelyDigressionSeparatesTangentsFromNoise() {
        assertEquals(false, isLikelyDigression("res"))
        assertEquals(false, isLikelyDigression("huh"))
        assertEquals(true, isLikelyDigression("what pairs well with steak?"))
        assertEquals(true, isLikelyDigression("what should I get for my anniversary dinner"))
    }

    @Test
    fun requestsFindWineSwitchIsExplicitNotGeneralRecommendation() {
        assertEquals(true, requestsFindWineSwitch("can you find me a wine"))
        assertEquals(true, requestsFindWineSwitch("let's find a wine for dinner"))
        assertEquals(false, requestsFindWineSwitch("what would you recommend with steak?"))
        assertEquals(false, requestsFindWineSwitch("any suggestions for a light lunch"))
    }

    /** None of these turns reach a digression or card-synthesis branch, so no real model file
     * is ever needed — the deterministic Q1-Q3 flow never touches the engine. */
    private fun newFindWineResponder(): GemmaConversationResponder =
        GemmaConversationResponder(
            ApplicationProvider.getApplicationContext(),
            File("nonexistent-model-file"),
        )

    @Test
    fun compoundReplyToQ1AlsoResolvesQ2AndSkipsStraightToQ3() = runBlocking {
        val responder = newFindWineResponder()
        responder.replyTo("find a wine")

        val response = responder.replyTo("French Red")

        assertEquals(ChatFlowText.Q3_TASTE, response.text)
    }

    @Test
    fun fullyDetailedReplyToQ1FinalizesOnboardingImmediately() = runBlocking {
        val responder = newFindWineResponder()
        responder.replyTo("find a wine")

        val response = responder.replyTo("full-bodied French red")

        assertEquals(true, response.coverageComplete)
        assertEquals("red", response.resolvedPreferences?.type)
        assertEquals("France", response.resolvedPreferences?.country)
        assertEquals("Full-Bodied", response.resolvedPreferences?.body)
    }

    @Test
    fun typeOnlyReplyStillAsksQ2AsBefore() = runBlocking {
        val responder = newFindWineResponder()
        responder.replyTo("find a wine")

        val response = responder.replyTo("red")

        assertEquals(ChatFlowText.Q2_COUNTRY, response.text)
    }

    /** Regression: declining Q3 with "no preference"/"none"/"nothing"/"skip" left every taste
     * field Unknown, so advanceFindWine (which walks preferences by field, not by turn) treated
     * Taste as still-unresolved and re-asked the same question forever instead of finalizing. */
    @Test
    fun decliningQ3WithNoPreferenceFinalizesInsteadOfRepeatingTheQuestion() = runBlocking {
        val responder = newFindWineResponder()
        responder.replyTo("find a wine")
        responder.replyTo("red")
        responder.replyTo("France")

        val response = responder.replyTo("no preference")

        assertEquals(true, response.coverageComplete)
        assertEquals("red", response.resolvedPreferences?.type)
        assertEquals("France", response.resolvedPreferences?.country)
    }

    @Test
    fun decliningQ3WithNoneNothingOrSkipAllFinalizeInsteadOfRepeating() = runBlocking {
        for (decline in listOf("none", "nothing", "skip")) {
            val responder = newFindWineResponder()
            responder.replyTo("find a wine")
            responder.replyTo("red")
            responder.replyTo("France")

            val response = responder.replyTo(decline)

            assertEquals("decline phrase: $decline", true, response.coverageComplete)
        }
    }

    /** Same bug also affected Q1/Q2: declining Type or Country never advanced past that step
     * since the declined field stayed Unknown — indistinguishable from "not yet answered" by
     * field value alone. */
    @Test
    fun decliningQ1AdvancesToQ2InsteadOfRepeatingItAndDecliningEverythingStillFinalizes() = runBlocking {
        val responder = newFindWineResponder()
        responder.replyTo("find a wine")

        val afterQ1 = responder.replyTo("no preference")
        assertEquals(ChatFlowText.Q2_COUNTRY, afterQ1.text)

        val afterQ2 = responder.replyTo("no preference")
        assertEquals(ChatFlowText.Q3_TASTE, afterQ2.text)

        val afterQ3 = responder.replyTo("no preference")
        assertEquals(true, afterQ3.coverageComplete)
    }
}
