package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GemmaConversationResponderTest {
    @Test
    fun stageOneIsBlockedUntilAllQuestionCoverageIsClosed() {
        assertEquals(
            true,
            GemmaConversationResponder.shouldBlockStageOne(
                hasStageOneOutput = true,
                coverageComplete = false,
            ),
        )
        assertEquals(
            false,
            GemmaConversationResponder.shouldBlockStageOne(
                hasStageOneOutput = true,
                coverageComplete = true,
            ),
        )
        assertEquals(
            false,
            GemmaConversationResponder.shouldBlockStageOne(
                hasStageOneOutput = false,
                coverageComplete = false,
            ),
        )
    }

    @Test
    fun firstOnboardingRequestFramesLaunchQuestionAndRequiresCoverage() {
        val request = GemmaConversationResponder.buildOnboardingRequest(
            coverage = WineFieldCoverage(),
            query = "Red",
            isFirstTurn = true,
        )

        assertEquals(true, request.contains("The app already asked question 1"))
        assertEquals(true, request.contains("Treat the user's latest message as their answer"))
        assertEquals(true, request.contains("User's latest message: Red"))
        assertEquals(true, request.contains("Coverage so far:"))
        assertEquals(true, request.contains("Current pending question: Q1 wine type"))
        assertEquals(true, request.contains("always emit [FIELD_COVERAGE]"))
    }

    @Test
    fun laterOnboardingRequestFramesLatestMessageAsAnswerToPreviousQuestion() {
        val request = GemmaConversationResponder.buildOnboardingRequest(
            coverage = WineFieldCoverage(q1Type = CoverageStatus.Closed),
            query = "France",
            isFirstTurn = false,
        )

        assertEquals(true, request.contains("immediately preceding reply"))
        assertEquals(true, request.contains("\"q1_type\":\"closed\""))
        assertEquals(true, request.contains("Current pending question: Q2 country"))
        assertEquals(true, request.contains("Never repeat a question"))
        assertEquals(true, request.contains("User's latest message: France"))
    }

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
    fun fieldCoverageRoundTripsAndIsHiddenFromVisibleText() {
        val response = """
            Which country sounds best: **France**, **Italy**, or **Spain**?
            [FIELD_COVERAGE]
            {"q1_type":"closed","q2_country":"clarify","q3_attributes":"clarify","event":"answer"}
            [/FIELD_COVERAGE]
        """.trimIndent()

        val coverage = GemmaConversationResponder.extractFieldCoverage(response)
        val visible = with(GemmaConversationResponder) { response.toVisibleResponse() }

        assertEquals(CoverageStatus.Closed, coverage?.q1Type)
        assertEquals(CoverageStatus.Clarify, coverage?.q2Country)
        assertEquals(false, coverage?.allClosed)
        assertEquals("Which country sounds best: **France**, **Italy**, or **Spain**?", visible)
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
              {"name":"Example Barolo","winery":"Example Winery","country":"Italy","province":"Piedmont","variety":"Nebbiolo","body":"full","tannin":"high","acidity":"high","flavor_notes":["rose","tar"],"suggested_pairing":"Unknown","web_summary":"A structured Nebbiolo supported by the supplied search results."}
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
              {"name":"Barolo","type":"red","variety":"Nebbiolo","country":"Italy","province":"Piedmont","flavor":"rose","occasion":"dinner","summary":"A structured Nebbiolo with floral aromas and firm tannin."},
              {"name":"Brunello di Montalcino","type":"red","variety":"Sangiovese","country":"Italy","province":"Tuscany","summary":"A full-bodied Tuscan red with savory cherry character."},
              {"name":"Etna Rosso","type":"red","variety":"Nerello Mascalese","country":"Italy","province":"Sicily","summary":"An elegant volcanic red with bright fruit and mineral notes."}
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
    fun stateSnapshotIsExtractedAndNeverShownToTheUser() {
        val response = """
            Would you prefer **light** or **full** body? [NEEDS_CLARIFICATION]
            [STATE_SNAPSHOT]
            {"type":"red","country":"Unknown","body":"Unknown","tannin":"Unknown","acidity":"Unknown","variety":"Unknown","flavor":"Unknown","occasion":"Unknown"}
            [/STATE_SNAPSHOT]
        """.trimIndent()

        val snapshot = GemmaConversationResponder.extractStateSnapshot(response)
        val visible = with(GemmaConversationResponder) { response.toVisibleResponse() }

        assertEquals("red", org.json.JSONObject(snapshot.orEmpty()).getString("type"))
        assertEquals("Would you prefer **light** or **full** body?", visible)
    }

    @Test
    fun malformedSnapshotLabelAndStructuredJsonAreHiddenWithoutFuzzyExtraction() {
        val response = """
            Would you prefer **France**, **Italy**, or **somewhere else**?
            *Snapshot resolved_state_snapshot:
            {"type":"red","country":"Unknown","body":"Unknown","tannin":"Unknown"}
        """.trimIndent()

        val snapshot = GemmaConversationResponder.extractStateSnapshot(response)
        val visible = with(GemmaConversationResponder) { response.toVisibleResponse() }

        assertNull(snapshot)
        assertEquals(
            "Would you prefer **France**, **Italy**, or **somewhere else**?",
            visible,
        )
    }

    @Test
    fun unmarkedFencedJsonIsNeverShown() {
        val response = """
            Let me narrow that down.
            ```json
            {"type":"red","country":"France","body":"full"}
            ```
        """.trimIndent()

        val visible = with(GemmaConversationResponder) { response.toVisibleResponse() }

        assertEquals("Let me narrow that down.", visible)
    }

    @Test
    fun rawCardArrayIsParsedButNeverIncludedInVisibleResponse() {
        val response = """
            These are three crisp options to explore.
            [
              {"name":"Chablis","type":"white","variety":"Chardonnay","country":"France","province":"Burgundy"},
              {"name":"Sancerre","type":"white","variety":"Sauvignon Blanc","country":"France","province":"Loire"},
              {"name":"Soave Classico","type":"white","variety":"Garganega","country":"Italy","province":"Veneto"}
            ]
        """.trimIndent()

        val suggestions = GemmaConversationResponder.extractSuggestions(response)
        val visible = with(GemmaConversationResponder) { response.toVisibleResponse() }

        assertEquals(3, suggestions.size)
        assertEquals("These are three crisp options to explore.", visible)
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
              "body": "high",
              "tannin": "high",
              "acidity": "high",
              "flavor_notes": "cherry, rose, tar",
              "suggested_pairing": "Braised beef",
              "summary": "$longSummary",
              "review_summary": "Model-generated critic text must be ignored",
              "web_summary": "Model-generated web text must be ignored"
            }
            [/WINE_PROFILE]
        """.trimIndent()

        val suggestion = GemmaConversationResponder.extractSuggestion(response)

        assertNotNull(suggestion)
        assertEquals("Barolo", suggestion?.name)
        assertEquals("Nebbiolo", suggestion?.variety)
        assertEquals("Italy", suggestion?.country)
        assertEquals("Piedmont", suggestion?.province)
        assertEquals("Braised beef", suggestion?.suggestedPairing)
        assertEquals(WineSuggestionSource.GEMMA, suggestion?.source)
        assertEquals(199, suggestion?.summary?.length)
        assertEquals("Unknown", suggestion?.reviewSummary)
        assertEquals("Unknown", suggestion?.webSummary)
        assertNull(suggestion?.rating)
    }

    @Test
    fun twoProfileMarkersParseAsTwoDistinctGemmaOptions() {
        val response = """
            I would try these two wines.
            [WINE_PROFILE]
            {"name":"Barolo","winery":"Unknown","country":"Italy","province":"Piedmont","variety":"Nebbiolo","body":"high","tannin":"high","acidity":"high","flavor_notes":"rose, tar","suggested_pairing":"Lamb"}
            [/WINE_PROFILE]
            [WINE_PROFILE]
            {"name":"Brunello","winery":"Unknown","country":"Italy","province":"Tuscany","variety":"Sangiovese","body":"high","tannin":"medium","acidity":"high","flavor_notes":"cherry, herbs","suggested_pairing":"Lamb"}
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

        val suggestion = GemmaConversationResponder.extractSuggestion(response)

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

        val suggestion = GemmaConversationResponder.extractSuggestion(response)

        assertEquals("red cherry, rose petal, tar", suggestion?.flavorNotes)
    }

    @Test
    fun streamingTextStopsBeforePartialOrCompleteHiddenProfileMarker() {
        assertEquals(
            "A lively red would work well.",
            with(GemmaConversationResponder) {
                "A lively red would work well. [".toStreamingVisibleResponse()
            },
        )
        assertEquals(
            "A lively red would work well.",
            with(GemmaConversationResponder) {
                "A lively red would work well. [WINE_CAR".toStreamingVisibleResponse()
            },
        )
        assertEquals(
            "A lively red would work well.",
            with(GemmaConversationResponder) {
                "A lively red would work well. [WINE_PRO".toStreamingVisibleResponse()
            },
        )
        assertEquals(
            "A lively red would work well.",
            with(GemmaConversationResponder) {
                "A lively red would work well. [WINE_PROFILE]{\"name\":\"Barolo\"}"
                    .toStreamingVisibleResponse()
            },
        )
        assertEquals(
            "Would you prefer red, white, rosé, or sparkling wine?",
            with(GemmaConversationResponder) {
                "Would you prefer red, white, rosé, or sparkling wine? [NEEDS_CLAR"
                    .toStreamingVisibleResponse()
            },
        )
    }
}
