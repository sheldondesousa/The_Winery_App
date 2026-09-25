package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.data.profile.CachedWineOption
import com.sheldondesousa.uncork.data.profile.WineOptionCache
import com.sheldondesousa.uncork.data.reviews.WineReview
import com.sheldondesousa.uncork.data.reviews.WineReviewCriteria
import com.sheldondesousa.uncork.data.reviews.WineReviewDataSource
import com.sheldondesousa.uncork.data.reviews.WineSelectionCriteria
import com.sheldondesousa.uncork.data.reviews.WineReviewKeywordQuery
import com.sheldondesousa.uncork.ui.conversation.ChatMessage
import com.sheldondesousa.uncork.ui.conversation.ConversationResponder
import com.sheldondesousa.uncork.ui.conversation.ConversationStreamUpdate
import com.sheldondesousa.uncork.ui.conversation.MessageAuthor
import com.sheldondesousa.uncork.ui.conversation.SourceQueryStatus
import com.sheldondesousa.uncork.ui.conversation.WineCardSynthesizer
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KaggleConversationResponderTest {
    @Test
    fun completedCoverageImmediatelyStartsKaggleLookup() = runBlocking {
        val dataSource = FakeWineReviewDataSource(selectionPoolResults = REVIEWS)
        val responder = KaggleConversationResponder(
            gemmaResponder = ConversationResponder {
                gemmaMessage(GEMMA_OPTIONS, coverageComplete = true)
            },
            wineReviewRepository = dataSource,
        )

        val response = responder.replyTo("Red. Italy. Full body.")

        assertEquals(listOf("Wine One", "Wine Two"), response.suggestions.map { it.name })
        assertTrue(dataSource.selectionPoolLookups.isNotEmpty())
        assertTrue(response.text.contains("other wine enthusiasts"))
    }

    @Test
    fun keywordFallbackResultsThatDontMatchTheRequestedCountryGetCloseAlternativesCopy() = runBlocking {
        // No rows for the requested country (e.g. "China" has none in the Kaggle dataset), so
        // the structured lookups miss and the loose any-term keyword fallback is what actually
        // returns cards — from unrelated countries. The response text must say so rather than
        // imply a genuine database match.
        val dataSource = FakeWineReviewDataSource(
            keywordResults = listOf(
                WineReview(
                    id = 9,
                    name = "Barolo",
                    winery = "Winery A",
                    country = "Italy",
                    province = "Piedmont",
                    variety = "Nebbiolo",
                    points = 95,
                    reviewSummary = "A fine Italian red.",
                ),
            ),
        )
        val gemmaOptions = listOf(
            WineSuggestion(name = "Unknown", winery = "Unknown", country = "China", province = "Unknown"),
        )
        val responder = KaggleConversationResponder(
            gemmaResponder = ConversationResponder {
                gemmaMessage(gemmaOptions, coverageComplete = true)
            },
            wineReviewRepository = dataSource,
        )

        val response = responder.replyTo("Red. China.")

        assertEquals("Italy", response.suggestions.single().country)
        assertTrue(response.text.contains("close alternatives"))
        assertTrue(!response.text.contains("other wine enthusiasts"))
    }

    @Test
    fun preferencesDrivenCompletionQueriesKaggleFromRecordedPreferencesNotGemmaCandidates() = runBlocking {
        val dataSource = FakeWineReviewDataSource(selectionPoolResults = REVIEWS)
        val recordedPreferences = WinePreferences(type = "red", country = "Italy", province = "Piedmont")
        val gemma = FakeCardSynthesizingGemma(cards = GEMMA_OPTIONS, preferences = recordedPreferences)
        val responder = KaggleConversationResponder(
            gemmaResponder = gemma,
            wineReviewRepository = dataSource,
        )

        val response = responder.replyTo("Full body")

        // Gemma and Kaggle both genuinely run (like Find's "AI Sommelier" + "Database"
        // sections), but Kaggle keeps "database first" priority for the winning suggestion
        // regardless of which one happens to resolve first in the race. There's no blanket
        // top-of-bubble text any more — each search-type card carries its own tag and text.
        assertEquals(listOf("Wine One", "Wine Two"), response.suggestions.map { it.name })
        assertEquals("", response.text)
        assertEquals(
            listOf("Wine One", "Wine Two"),
            response.sourceResults.single { it.source == WineSuggestionSource.KAGGLE }.suggestions.map { it.name },
        )
        assertEquals(
            WineSelectionCriteria(wineType = "red", country = "Italy", province = "Piedmont"),
            dataSource.selectionPoolLookups.single(),
        )
        // The structured query came from WinePreferences, not from parsing "Full body" or from
        // reading fields back out of Gemma's returned candidates.
        assertEquals(
            setOf(WineSuggestionSource.GEMMA, WineSuggestionSource.KAGGLE, WineSuggestionSource.CACHE),
            response.sourceResults.map { it.source }.toSet(),
        )
    }

    @Test
    fun preferencesDrivenCacheHitKeepsDatabaseFirstPriorityOverGemma() = runBlocking {
        val recordedPreferences = WinePreferences(type = "red", country = "Italy", province = "Piedmont")
        var matchedCriteria: WineSelectionCriteria? = null
        val cache = object : WineOptionCache {
            override suspend fun find(country: String, province: String, variety: String): CachedWineOption? = null

            override suspend fun findMatching(criteria: WineSelectionCriteria): List<CachedWineOption> {
                matchedCriteria = criteria
                return listOf(
                    CachedWineOption(
                        name = "Cached Barolo",
                        winery = "Cached Winery",
                        country = "Italy",
                        province = "Piedmont",
                        variety = "Nebbiolo",
                        body = "Full",
                        tannin = "High",
                        acidity = "High",
                        flavorNotes = listOf("rose", "tar"),
                        suggestedPairing = "Braised beef",
                        webSummary = "Saved from an earlier web search.",
                    ),
                )
            }
        }
        val gemma = FakeCardSynthesizingGemma(cards = GEMMA_OPTIONS, preferences = recordedPreferences)
        val responder = KaggleConversationResponder(
            gemmaResponder = gemma,
            wineReviewRepository = FakeWineReviewDataSource(),
            optionCache = cache,
        )

        val response = responder.replyTo("Full body")

        // Cache wins the "database first" priority for the summary suggestion — Gemma also
        // genuinely ran (with real cards) and Kaggle genuinely missed, but neither outranks a
        // real cache hit. No blanket top-of-bubble text any more — the cache's own card says so.
        assertEquals("Cached Barolo", response.suggestions.single().name)
        assertEquals("", response.text)
        assertEquals(
            WineSelectionCriteria(wineType = "red", country = "Italy", province = "Piedmont"),
            matchedCriteria,
        )
        // Gemma and Kaggle race concurrently (order between them isn't guaranteed); cache always
        // resolves last since it only starts once Kaggle is complete. Kaggle's real miss still
        // gets its own annotated section rather than being silently omitted.
        assertEquals(3, response.sourceResults.size)
        assertEquals(WineSuggestionSource.CACHE, response.sourceResults.last().source)
        assertEquals(
            setOf(WineSuggestionSource.GEMMA, WineSuggestionSource.KAGGLE),
            response.sourceResults.take(2).map { it.source }.toSet(),
        )
        assertTrue(response.sourceResults.all { it.status == SourceQueryStatus.COMPLETE })
        assertTrue(response.sourceResults.first { it.source == WineSuggestionSource.KAGGLE }.suggestions.isEmpty())
        assertEquals(
            GEMMA_OPTIONS.map { it.name },
            response.sourceResults.first { it.source == WineSuggestionSource.GEMMA }.suggestions.map { it.name },
        )
        assertEquals(
            "Cached Barolo",
            response.sourceResults.first { it.source == WineSuggestionSource.CACHE }.suggestions.single().name,
        )
    }

    @Test
    fun webSearchIsOnlyOfferedAsAFollowUpNotRunAutomatically() = runBlocking {
        val recordedPreferences = WinePreferences(type = "red", country = "Italy", province = "Piedmont")
        var webRequest: WineWebSearchRequest? = null
        val webSearch = WineWebSearchDataSource { request ->
            webRequest = request
            listOf(
                WineSuggestion(
                    name = "Online Barolo",
                    winery = "Online Winery",
                    country = "Italy",
                    province = "Piedmont",
                ),
            )
        }
        val gemma = FakeCardSynthesizingGemma(cards = GEMMA_OPTIONS, preferences = recordedPreferences)
        val responder = KaggleConversationResponder(
            gemmaResponder = gemma,
            wineReviewRepository = FakeWineReviewDataSource(),
            webSearch = webSearch,
        )

        val firstResponse = responder.replyTo("Full body")

        // Kaggle and cache both missed, so Gemma's own (genuinely run) cards win the summary —
        // web search never ran; it's only offered, same as the pre-existing follow-up pattern.
        assertEquals(WineSuggestionSource.GEMMA, firstResponse.suggestions.first().source)
        assertEquals("Would you like me to run a broader web search?", firstResponse.followUpText)
        assertNull(webRequest)

        val webResponse = responder.replyTo("yes")

        assertEquals("Online Barolo", webResponse.suggestions.single().name)
        assertEquals(WineSuggestionSource.WEB_SEARCH, webResponse.suggestions.single().source)
        // The follow-up web query used Gemma's own resolved cards as search hints.
        assertEquals(GEMMA_OPTIONS.map { it.name }, webRequest?.gemmaSuggestions?.map { it.name })
    }

    @Test
    fun gemmaStaysListedAsLoadingWhenTheWebSearchQuestionIsPublishedMidTurn() = runBlocking {
        // Regression: the mid-turn publish (fired once Kaggle/cache settle, before Gemma is
        // necessarily done) built its sourceResults from progress.results, which only returns
        // already-resolved sources — so a still-running Gemma was silently dropped from the list
        // entirely at exactly that moment, making its loading card vanish from the UI instead of
        // continuing to show as loading.
        val recordedPreferences = WinePreferences(type = "red", country = "Italy", province = "Piedmont")
        val gemma = FakeCardSynthesizingGemma(
            cards = GEMMA_OPTIONS,
            preferences = recordedPreferences,
            synthesisDelayMs = 50,
        )
        val responder = KaggleConversationResponder(
            gemmaResponder = gemma,
            wineReviewRepository = FakeWineReviewDataSource(selectionPoolResults = REVIEWS),
        )
        val updates = mutableListOf<ConversationStreamUpdate>()

        responder.replyToUpdates("Full body", updates::add)

        val webQuestionUpdate = updates.first { it.followUpText != null }
        assertTrue(
            "Gemma missing from mid-turn sourceResults: ${webQuestionUpdate.sourceResults.map { it.source }}",
            webQuestionUpdate.sourceResults.any { it.source == WineSuggestionSource.GEMMA },
        )
    }

    @Test
    fun webSearchFailureShowsARetryInsteadOfFallingThroughToTheModeChoiceApology() = runBlocking {
        // Regression: a network failure (or the request being interrupted, e.g. the user
        // switching tabs mid-search) was indistinguishable from "ran and found nothing", and
        // either way left the pending state cleared — so the user's next message was treated as
        // a brand-new top-level turn, producing the confusing "I'm sorry I couldn't quite catch
        // that. Hi, I'm UnCork..." mode-choice apology instead of a sane retry affordance.
        val recordedPreferences = WinePreferences(type = "red", country = "Italy", province = "Piedmont")
        val gemma = FakeCardSynthesizingGemma(cards = GEMMA_OPTIONS, preferences = recordedPreferences)
        var searchAttempts = 0
        val webSearch = WineWebSearchDataSource {
            searchAttempts++
            if (searchAttempts == 1) throw java.io.IOException("no network")
            listOf(WineSuggestion(name = "Online Barolo", country = "Italy", province = "Piedmont"))
        }
        val responder = KaggleConversationResponder(
            gemmaResponder = gemma,
            wineReviewRepository = FakeWineReviewDataSource(),
            webSearch = webSearch,
        )
        responder.replyTo("Full body")

        val failedResponse = responder.replyTo("yes")

        assertTrue(failedResponse.suggestions.isEmpty())
        assertEquals(
            listOf(WineSuggestionSource.WEB_SEARCH to SourceQueryStatus.FAILED),
            failedResponse.sourceResults.map { it.source to it.status },
        )

        // A subsequent "try again" (or "yes") resumes the same pending web search rather than
        // falling through to a fresh top-level turn.
        val retriedResponse = responder.replyTo("Try Again")

        assertEquals("Online Barolo", retriedResponse.suggestions.singleOrNull()?.name)
        assertEquals(2, searchAttempts)
    }

    @Test
    fun shortAffirmativeRepliesToTheWebSearchQuestionAllRunTheSearch() = runBlocking {
        // Regression: "Y" (and its siblings) used to fail isAffirmativeReply, fall through the
        // pending-web-search branch entirely, and get treated as a brand-new message to Gemma —
        // producing "I'm sorry I couldn't quite catch that" followed by the mode-choice greeting
        // instead of running the web search.
        for (reply in listOf("Y", "y", "Yup", "Go", "Yeah", "Ya")) {
            val webSearch = WineWebSearchDataSource {
                listOf(WineSuggestion(name = "Online Barolo", country = "Italy", province = "Piedmont"))
            }
            val recordedPreferences = WinePreferences(type = "red", country = "Italy", province = "Piedmont")
            val gemma = FakeCardSynthesizingGemma(cards = GEMMA_OPTIONS, preferences = recordedPreferences)
            val responder = KaggleConversationResponder(
                gemmaResponder = gemma,
                wineReviewRepository = FakeWineReviewDataSource(),
                webSearch = webSearch,
            )
            responder.replyTo("Full body")

            val webResponse = responder.replyTo(reply)

            assertEquals("reply: $reply", "Online Barolo", webResponse.suggestions.singleOrNull()?.name)
            assertEquals("reply: $reply", WineSuggestionSource.WEB_SEARCH, webResponse.suggestions.single().source)
        }
    }

    @Test
    fun shortNegativeRepliesToTheWebSearchQuestionAllDeclineIt() = runBlocking {
        for (reply in listOf("N", "n", "Na", "Nope", "Nah")) {
            var webSearchCalled = false
            val webSearch = WineWebSearchDataSource {
                webSearchCalled = true
                emptyList()
            }
            val recordedPreferences = WinePreferences(type = "red", country = "Italy", province = "Piedmont")
            val gemma = FakeCardSynthesizingGemma(cards = GEMMA_OPTIONS, preferences = recordedPreferences)
            val responder = KaggleConversationResponder(
                gemmaResponder = gemma,
                wineReviewRepository = FakeWineReviewDataSource(),
                webSearch = webSearch,
            )
            responder.replyTo("Full body")

            val declineResponse = responder.replyTo(reply)

            assertTrue("reply: $reply", declineResponse.text.contains("keep those recommendations"))
            assertTrue("reply: $reply", !webSearchCalled)
        }
    }

    @Test
    fun gemmaAlwaysRunsAlongsideKaggleRegardlessOfOutcome() = runBlocking {
        val dataSource = FakeWineReviewDataSource()
        val recordedPreferences = WinePreferences(type = "red", country = "Italy", province = "Piedmont")
        val gemma = FakeCardSynthesizingGemma(cards = GEMMA_OPTIONS, preferences = recordedPreferences)
        val responder = KaggleConversationResponder(
            gemmaResponder = gemma,
            wineReviewRepository = dataSource,
        )

        val response = responder.replyTo("Full body")

        // Gemma is invoked on every Q3 completion, concurrently with Kaggle — never skipped just
        // because Kaggle happens to miss (or hit).
        assertTrue(dataSource.selectionPoolLookups.isNotEmpty())
        assertEquals(recordedPreferences, gemma.receivedPreferences)
        assertTrue(response.suggestions.isNotEmpty() || response.text.isNotBlank())
    }

    @Test
    fun attributeFilteredKaggleMissStopsBeforeCacheAndWeb() = runBlocking {
        var cacheWasCalled = false
        var webWasCalled = false
        val responder = KaggleConversationResponder(
            gemmaResponder = ConversationResponder {
                gemmaMessage(GEMMA_OPTIONS, coverageComplete = true)
            },
            wineReviewRepository = FakeWineReviewDataSource(),
            optionCache = WineOptionCache { _, _, _ ->
                cacheWasCalled = true
                null
            },
            webSearch = WineWebSearchDataSource {
                webWasCalled = true
                emptyList()
            },
        )

        val response = responder.replyTo("Red. Full body.")

        assertTrue(response.suggestions.isEmpty())
        assertTrue(response.text.contains("body (Full)"))
        assertTrue(!cacheWasCalled)
        assertTrue(!webWasCalled)
    }

    @Test
    fun completedProfilesStreamAsCardsBeforeGenerationFinishes() = runBlocking {
        val updates = mutableListOf<ConversationStreamUpdate>()
        val streamingGemma = object : ConversationResponder {
            override suspend fun replyTo(query: String): ChatMessage = gemmaMessage(GEMMA_OPTIONS)

            override suspend fun replyToUpdates(
                query: String,
                onUpdate: (ConversationStreamUpdate) -> Unit,
            ): ChatMessage {
                onUpdate(
                    ConversationStreamUpdate(
                        text = "Two recommendations.",
                        suggestions = listOf(GEMMA_OPTIONS.first()),
                    ),
                )
                onUpdate(
                    ConversationStreamUpdate(
                        text = "Two recommendations.",
                        suggestions = GEMMA_OPTIONS,
                    ),
                )
                return gemmaMessage(GEMMA_OPTIONS)
            }
        }
        val responder = KaggleConversationResponder(streamingGemma, FakeWineReviewDataSource())

        responder.replyToUpdates("wine for lamb", updates::add)

        assertEquals(listOf(1, 3), updates.map { it.suggestions.size })
        assertEquals("Barolo", updates.first().suggestions.single().name)
    }

    @Test
    fun clarificationQuestionDoesNotTriggerAnyFallbackSource() = runBlocking {
        val dataSource = FakeWineReviewDataSource(exactResults = REVIEWS, keywordResults = REVIEWS)
        var cacheWasCalled = false
        var webWasCalled = false
        val responder = KaggleConversationResponder(
            gemmaResponder = ConversationResponder {
                ChatMessage(
                    id = 1L,
                    author = MessageAuthor.Assistant,
                    text = "Would you prefer red, white, rosé, or sparkling wine?",
                    needsClarification = true,
                )
            },
            wineReviewRepository = dataSource,
            optionCache = WineOptionCache { _, _, _ ->
                cacheWasCalled = true
                null
            },
            webSearch = WineWebSearchDataSource {
                webWasCalled = true
                emptyList()
            },
        )

        val response = responder.replyTo("Recommend a wine")

        assertTrue(response.needsClarification)
        assertTrue(response.text.endsWith('?'))
        assertTrue(response.suggestions.isEmpty())
        assertTrue(dataSource.exactLookups.isEmpty())
        assertEquals(null, dataSource.keywordLookup)
        assertTrue(!cacheWasCalled)
        assertTrue(!webWasCalled)
    }

    @Test
    fun gemmaVisibleTextStreamsBeforeTheCompletedOptions() = runBlocking {
        val partials = mutableListOf<String>()
        val streamingGemma = object : ConversationResponder {
            override suspend fun replyTo(query: String): ChatMessage = gemmaMessage(GEMMA_OPTIONS)

            override suspend fun replyToStreaming(
                query: String,
                onPartialText: (String) -> Unit,
            ): ChatMessage {
                onPartialText("Here are two")
                onPartialText("Here are two recommendations.")
                return gemmaMessage(GEMMA_OPTIONS)
            }
        }
        val responder = KaggleConversationResponder(streamingGemma, FakeWineReviewDataSource())

        val response = responder.replyToStreaming("wine for lamb", partials::add)

        assertEquals(listOf("Here are two", "Here are two recommendations."), partials)
        assertEquals(listOf("Barolo", "Brunello", "Rioja"), response.suggestions.map(WineSuggestion::name))
    }

    @Test
    fun upToThreeCompleteGemmaOptionsAreShownBeforeAnyKaggleQuery() = runBlocking {
        val dataSource = FakeWineReviewDataSource(exactResults = REVIEWS)
        val responder = KaggleConversationResponder(twoOptionGemmaResponder(), dataSource)

        val response = responder.replyTo("wine for lamb")

        assertEquals(listOf("Barolo", "Brunello", "Rioja"), response.suggestions.map(WineSuggestion::name))
        assertTrue(response.followUpText?.contains("wine enthusiasts") == true)
        assertTrue(dataSource.exactLookups.isEmpty())
        assertEquals(null, dataSource.keywordLookup)
    }

    @Test
    fun yesQueriesKaggleForTheOriginalGemmaProfiles() = runBlocking {
        val dataSource = FakeWineReviewDataSource(exactResults = REVIEWS)
        val responder = KaggleConversationResponder(twoOptionGemmaResponder(), dataSource)
        responder.replyTo("wine for lamb")

        val response = responder.replyTo("Yes, please")

        assertEquals(
            listOf(
                Triple("Italy", "Piedmont", "Nebbiolo"),
                Triple("Italy", "Tuscany", "Sangiovese"),
                Triple("Spain", "Rioja", "Tempranillo"),
            ),
            dataSource.exactLookups,
        )
        assertEquals(listOf("Wine One", "Wine Two"), response.suggestions.map(WineSuggestion::name))
        assertEquals(listOf(99, 97), response.suggestions.map(WineSuggestion::rating))
        assertTrue(response.suggestions.all { it.source == WineSuggestionSource.KAGGLE })
        assertTrue(response.suggestions.all { it.summary == "Unknown" })
        assertEquals("wine for lamb", response.historyRequest)
        assertEquals("Would you like me to run a broader web search?", response.followUpText)
    }

    @Test
    fun enthusiastRequestUsesEveryCompleteCountryProvinceVarietyProfile() = runBlocking {
        val optionsWithOneCompleteProfile = listOf(
            GEMMA_OPTIONS[0],
            GEMMA_OPTIONS[1].copy(province = "Unknown"),
            GEMMA_OPTIONS[2].copy(variety = "Unknown"),
        )
        val dataSource = FakeWineReviewDataSource(exactResults = REVIEWS)
        val responder = KaggleConversationResponder(
            gemmaResponder = ConversationResponder { gemmaMessage(optionsWithOneCompleteProfile) },
            wineReviewRepository = dataSource,
        )

        val gemmaResponse = responder.replyTo("Recommend three structured red wines")
        val enthusiastResponse = responder.replyTo("Yes, please")

        assertEquals(3, gemmaResponse.suggestions.size)
        assertEquals(
            listOf(Triple("Italy", "Piedmont", "Nebbiolo")),
            dataSource.exactLookups,
        )
        assertEquals(listOf("Wine One", "Wine Two"), enthusiastResponse.suggestions.map { it.name })
        assertTrue(enthusiastResponse.suggestions.all { it.source == WineSuggestionSource.KAGGLE })
        assertEquals(null, dataSource.keywordLookup)
    }

    @Test
    fun sharedCountryIsTheOnlyStructuredKaggleCriterion() = runBlocking {
        val italianOptions = listOf(
            GEMMA_OPTIONS[0],
            GEMMA_OPTIONS[1],
            WineSuggestion(
                name = "Etna Rosso",
                country = "Italy",
                province = "Sicily",
                variety = "Nerello Mascalese",
            ),
        )
        val dataSource = FakeWineReviewDataSource(exactResults = REVIEWS)
        val responder = KaggleConversationResponder(
            gemmaResponder = ConversationResponder { gemmaMessage(italianOptions) },
            wineReviewRepository = dataSource,
        )

        responder.replyTo("Recommend three Italian red wines")
        val response = responder.replyTo("Yes")

        assertEquals(
            listOf(WineReviewCriteria(country = "Italy")),
            dataSource.criteriaLookups,
        )
        assertEquals(2, response.suggestions.size)
        assertEquals(null, dataSource.keywordLookup)
    }

    @Test
    fun sharedCountryAndProvinceAreBothUsedWithoutRequiringVariety() = runBlocking {
        val piedmontOptions = listOf(
            GEMMA_OPTIONS[0],
            GEMMA_OPTIONS[0].copy(name = "Barbera d'Alba", variety = "Barbera"),
            GEMMA_OPTIONS[0].copy(name = "Dolcetto d'Alba", variety = "Dolcetto"),
        )
        val dataSource = FakeWineReviewDataSource(exactResults = REVIEWS)
        val responder = KaggleConversationResponder(
            gemmaResponder = ConversationResponder { gemmaMessage(piedmontOptions) },
            wineReviewRepository = dataSource,
        )

        responder.replyTo("Recommend red wines from Piedmont, Italy")
        responder.replyTo("Yes")

        assertEquals(
            listOf(WineReviewCriteria(country = "Italy", province = "Piedmont")),
            dataSource.criteriaLookups,
        )
    }

    @Test
    fun fullBodiedRedReachesKaggleKeywordsAfterStructuredProfilesMiss() = runBlocking {
        val thirdReview = REVIEWS.first().copy(
            id = 3,
            name = "Wine Three",
            winery = "Winery C",
            points = 96,
        )
        val dataSource = FakeWineReviewDataSource(keywordResults = REVIEWS + thirdReview)
        val responder = KaggleConversationResponder(twoOptionGemmaResponder(), dataSource)

        val gemmaResponse = responder.replyTo("I asked for a full-bodied red wine")
        val kaggleResponse = responder.replyTo("Yes, show me enthusiast suggestions")

        assertEquals(3, gemmaResponse.suggestions.size)
        assertEquals(3, dataSource.exactLookups.size)
        assertEquals(
            "\"full\" AND \"bodied\" AND \"red\"",
            WineReviewKeywordQuery.from(dataSource.keywordLookup.orEmpty()),
        )
        assertEquals(3, kaggleResponse.suggestions.size)
        assertTrue(kaggleResponse.suggestions.all { it.source == WineSuggestionSource.KAGGLE })
        assertEquals("Would you like me to run a broader web search?", kaggleResponse.followUpText)
    }

    @Test
    fun roseClarificationThenEnthusiastRequestReturnsKaggleCards() = runBlocking {
        var gemmaTurn = 0
        val gemma = ConversationResponder {
            gemmaTurn += 1
            if (gemmaTurn == 1) {
                clarificationMessage(
                    "Do you have a country preference, or thoughts on body or acidity?",
                )
            } else {
                gemmaMessage(GEMMA_OPTIONS)
            }
        }
        val thirdReview = REVIEWS.first().copy(
            id = 3,
            name = "Wine Three",
            winery = "Winery C",
            points = 96,
        )
        val dataSource = FakeWineReviewDataSource(keywordResults = REVIEWS + thirdReview)
        val responder = KaggleConversationResponder(gemma, dataSource)

        val clarification = responder.replyTo("Rosé")
        val gemmaResponse = responder.replyTo("No country or attribute preference")
        val kaggleResponse = responder.replyTo("Yes")

        assertTrue(clarification.needsClarification)
        assertEquals(3, gemmaResponse.suggestions.size)
        assertEquals(3, dataSource.exactLookups.size)
        assertEquals("Rosé. No country or attribute preference", dataSource.keywordLookup)
        assertEquals(
            "{name variety} : \"rosé\"",
            WineReviewKeywordQuery.from(dataSource.keywordLookup.orEmpty()),
        )
        assertEquals(3, kaggleResponse.suggestions.size)
        assertTrue(kaggleResponse.suggestions.all { it.source == WineSuggestionSource.KAGGLE })
    }

    @Test
    fun kaggleMissUsesTheSameCriteriaForWebSearch() = runBlocking {
        var webRequest: WineWebSearchRequest? = null
        val webOption = WineSuggestion(
            name = "Online Barolo",
            winery = "Online Winery",
            country = "Italy",
            province = "Piedmont",
            variety = "Nebbiolo",
            webSummary = "Found online for the supplied preferences.",
        )
        val responder = KaggleConversationResponder(
            gemmaResponder = invalidNamedOptionGemmaResponder(),
            wineReviewRepository = FakeWineReviewDataSource(),
            webSearch = WineWebSearchDataSource { request ->
                webRequest = request
                listOf(webOption)
            },
        )
        val response = responder.replyTo("Italian red for lamb")

        assertEquals("Italian red for lamb", webRequest?.originalQuery)
        assertEquals("Online Barolo", response.suggestions.single().name)
        assertEquals(WineSuggestionSource.WEB_SEARCH, response.suggestions.single().source)
    }

    @Test
    fun threeGemmaCardsOfferKaggleThenKaggleCardsOfferBroaderWebSearch() = runBlocking {
        var webRequest: WineWebSearchRequest? = null
        val webOption = WineSuggestion(
            name = "Online Rioja Reserva",
            winery = "Online Winery",
            country = "Spain",
            province = "Rioja",
            variety = "Tempranillo",
            webSummary = "A broader-search alternative.",
        )
        val responder = KaggleConversationResponder(
            gemmaResponder = twoOptionGemmaResponder(),
            wineReviewRepository = FakeWineReviewDataSource(exactResults = REVIEWS),
            webSearch = WineWebSearchDataSource { request ->
                webRequest = request
                listOf(webOption)
            },
        )

        val gemmaResponse = responder.replyTo("A red wine for lamb")
        val kaggleResponse = responder.replyTo("Yes")
        val webResponse = responder.replyTo("Yes, search more broadly")

        assertEquals(3, gemmaResponse.suggestions.size)
        assertTrue(gemmaResponse.followUpText?.contains("wine enthusiasts") == true)
        assertTrue(kaggleResponse.suggestions.all { it.source == WineSuggestionSource.KAGGLE })
        assertEquals("Would you like me to run a broader web search?", kaggleResponse.followUpText)
        assertEquals("A red wine for lamb", webRequest?.originalQuery)
        assertEquals("Online Rioja Reserva", webResponse.suggestions.single().name)
    }

    @Test
    fun clarificationAnswersCarryIntoUpToThreeEnthusiastSuggestions() = runBlocking {
        var gemmaTurn = 0
        val gemma = ConversationResponder {
            gemmaTurn += 1
            when (gemmaTurn) {
                1 -> clarificationMessage("Would you prefer red, white, rosé, or sparkling wine?")
                2 -> clarificationMessage(
                    "Do you have a country, attribute, food pairing, or occasion in mind?",
                )
                else -> gemmaMessage(GEMMA_OPTIONS)
            }
        }
        val thirdReview = REVIEWS.first().copy(
            id = 3,
            name = "Wine Three",
            winery = "Winery C",
            points = 96,
        )
        val dataSource = FakeWineReviewDataSource(exactResults = REVIEWS + thirdReview)
        val responder = KaggleConversationResponder(gemma, dataSource)

        assertTrue(responder.replyTo("Recommend a wine").needsClarification)
        assertTrue(responder.replyTo("Red").needsClarification)
        val gemmaOptions = responder.replyTo("Italy for an anniversary dinner")
        val enthusiastOptions = responder.replyTo("Yes")

        assertEquals(
            "Recommend a wine. Red. Italy for an anniversary dinner",
            gemmaOptions.historyRequest,
        )
        assertEquals(3, enthusiastOptions.suggestions.size)
        assertEquals(
            "Recommend a wine. Red. Italy for an anniversary dinner",
            enthusiastOptions.historyRequest,
        )
    }

    @Test
    fun uncertainSecondAnswerProceedsWithTheKnownPreferences() = runBlocking {
        var gemmaTurn = 0
        val gemma = ConversationResponder {
            gemmaTurn += 1
            when (gemmaTurn) {
                1 -> clarificationMessage("Would you prefer red, white, rosé, or sparkling wine?")
                2 -> clarificationMessage(
                    "Would you like full body, noticeable tannin, bright acidity, or help with those terms?",
                )
                else -> gemmaMessage(GEMMA_OPTIONS)
            }
        }
        val dataSource = FakeWineReviewDataSource(keywordResults = REVIEWS)
        val responder = KaggleConversationResponder(gemma, dataSource)

        val typeQuestion = responder.replyTo("Recommend a wine")
        val attributeQuestion = responder.replyTo("Red")
        val options = responder.replyTo("I'm not sure")

        assertTrue(typeQuestion.needsClarification)
        assertTrue(attributeQuestion.needsClarification)
        assertTrue(!options.needsClarification)
        assertEquals(3, options.suggestions.size)
        assertEquals("Recommend a wine. Red", options.historyRequest)
        assertTrue(dataSource.exactLookups.isEmpty())
        assertEquals(null, dataSource.keywordLookup)
    }

    @Test
    fun gemmaReceivesEveryOnboardingAnswerAndLeadsEachTurn() = runBlocking {
        val receivedQueries = mutableListOf<String>()
        val gemma = ConversationResponder { query ->
            receivedQueries += query
            when (receivedQueries.size) {
                1 -> clarificationMessage("Would you prefer **country** or **body**?")
                2 -> clarificationMessage("Should I consider **tannin** or **acidity**?")
                else -> gemmaMessage(GEMMA_OPTIONS)
            }
        }
        val responder = KaggleConversationResponder(gemma, FakeWineReviewDataSource())

        val q2 = responder.replyTo("Red")
        val q3 = responder.replyTo("France and bold")
        val options = responder.replyTo("High tannin")

        assertTrue(q2.needsClarification)
        assertTrue(q3.needsClarification)
        assertEquals(listOf("Red", "France and bold", "High tannin"), receivedQueries)
        assertEquals(3, options.suggestions.size)
        assertEquals("Red. France and bold. High tannin", options.historyRequest)
    }

    @Test
    fun unclearAndUncertainAnswersArePassedToGemmaWithoutKotlinRewriting() = runBlocking {
        val receivedQueries = mutableListOf<String>()
        val gemma = ConversationResponder { query ->
            receivedQueries += query
            clarificationMessage("Gemma chose the next conversational response")
        }
        val responder = KaggleConversationResponder(gemma, FakeWineReviewDataSource())

        val unclear = responder.replyTo("def")
        val uncertain = responder.replyTo("I'm not sure")

        assertTrue(unclear.needsClarification)
        assertTrue(uncertain.needsClarification)
        assertEquals(listOf("def", "I'm not sure"), receivedQueries)
    }

    @Test
    fun redAndBoldUsesThreeGemmaCardsBeforeKaggle() = runBlocking {
        var turn = 0
        val gemma = ConversationResponder {
            turn += 1
            when (turn) {
                1, 2 -> clarificationMessage("Gemma onboarding question")
                else -> gemmaMessage(GEMMA_OPTIONS)
            }
        }
        val dataSource = FakeWineReviewDataSource(exactResults = REVIEWS)
        val responder = KaggleConversationResponder(gemma, dataSource)

        responder.replyTo("Red")
        responder.replyTo("Bold")
        val response = responder.replyTo("Choose for me")

        assertEquals(3, response.suggestions.size)
        assertTrue(response.suggestions.all { it.source == WineSuggestionSource.GEMMA })
        assertTrue(response.followUpText?.contains("wine enthusiasts") == true)
        assertTrue(dataSource.exactLookups.isEmpty())
        assertEquals(null, dataSource.keywordLookup)
    }

    @Test
    fun typeMismatchedGemmaCardIsDroppedWithoutSubstitution() = runBlocking {
        val matching = GEMMA_OPTIONS.take(2).map { it.copy(wineType = "red") }
        val responder = KaggleConversationResponder(
            gemmaResponder = ConversationResponder {
                gemmaMessage(matching).copy(discardedStageOneCards = 1)
            },
            wineReviewRepository = FakeWineReviewDataSource(exactResults = REVIEWS),
        )

        val response = responder.replyTo("Red")

        assertEquals(2, response.suggestions.size)
        assertTrue(response.suggestions.all { it.wineType == "red" })
        assertTrue(response.suggestions.all { it.source == WineSuggestionSource.GEMMA })
    }

    @Test
    fun redAndBoldDefineKaggleCandidatePoolWhenGemmaHasNoUsableCards() = runBlocking {
        var turn = 0
        val gemma = ConversationResponder {
            turn += 1
            if (turn < 3) clarificationMessage("Gemma onboarding question")
            else gemmaMessage(listOf(GEMMA_OPTIONS.first().copy(name = "Unknown")))
        }
        val rankedReviews = listOf(
            REVIEWS[0].copy(id = 11, name = "Top Red", points = 100, winery = "Alpha"),
            REVIEWS[1].copy(id = 12, name = "Second Red", points = 99, winery = "Beta"),
            REVIEWS[0].copy(id = 13, name = "Third Red", points = 98, winery = "Gamma"),
        )
        val dataSource = FakeWineReviewDataSource(selectionPoolResults = rankedReviews)
        val responder = KaggleConversationResponder(gemma, dataSource)

        responder.replyTo("Red")
        responder.replyTo("Bold")
        val response = responder.replyTo("Choose for me")

        assertEquals(
            listOf(WineSelectionCriteria(wineType = "red", body = "Full")),
            dataSource.selectionPoolLookups,
        )
        assertEquals(listOf("Top Red", "Second Red", "Third Red"), response.suggestions.map { it.name })
        assertTrue(response.suggestions.all { it.source == WineSuggestionSource.KAGGLE })
    }

    @Test
    fun suppliedCountryNarrowsPoolWhileUnspecifiedProvinceAndVarietyRemainOpen() {
        val responder = KaggleConversationResponder(
            gemmaResponder = invalidNamedOptionGemmaResponder(),
            wineReviewRepository = FakeWineReviewDataSource(),
        )

        val criteria = responder.buildSelectionCriteria(
            originalQuery = "A bold Italian red with high tannin",
            gemmaSuggestions = GEMMA_OPTIONS,
        )

        assertEquals("red", criteria.wineType)
        assertEquals("Italy", criteria.country)
        assertEquals(null, criteria.province)
        assertEquals(null, criteria.variety)
        assertEquals("Full", criteria.body)
        assertEquals("High", criteria.tannin)
    }

    @Test
    fun noEndsTheOfferWithoutQueryingFallbackSources() = runBlocking {
        val dataSource = FakeWineReviewDataSource(exactResults = REVIEWS)
        val responder = KaggleConversationResponder(twoOptionGemmaResponder(), dataSource)
        responder.replyTo("wine for lamb")

        val response = responder.replyTo("No thanks")

        assertTrue(response.text.contains("keep those recommendations"))
        assertTrue(response.suggestions.isEmpty())
        assertTrue(dataSource.exactLookups.isEmpty())
    }

    @Test
    fun naturalPositiveConfirmationsOpenTheEnthusiastDatabase() = runBlocking {
        val confirmations = listOf(
            "Yup",
            "Absolutely",
            "Certainly",
            "Go ahead",
            "Sounds good",
            "Why not?",
            "I’d like that",
        )

        confirmations.forEach { confirmation ->
            val dataSource = FakeWineReviewDataSource(exactResults = REVIEWS)
            val responder = KaggleConversationResponder(twoOptionGemmaResponder(), dataSource)
            responder.replyTo("wine for lamb")

            val response = responder.replyTo(confirmation)

            assertTrue("Expected '$confirmation' to open Kaggle", dataSource.exactLookups.isNotEmpty())
            assertTrue(response.suggestions.all { it.source == WineSuggestionSource.KAGGLE })
        }
    }

    @Test
    fun emphaticNegativeConfirmationDoesNotOpenTheEnthusiastDatabase() = runBlocking {
        val dataSource = FakeWineReviewDataSource(exactResults = REVIEWS)
        val responder = KaggleConversationResponder(twoOptionGemmaResponder(), dataSource)
        responder.replyTo("wine for lamb")

        val response = responder.replyTo("Absolutely not")

        assertTrue(response.text.contains("keep those recommendations"))
        assertTrue(dataSource.exactLookups.isEmpty())
    }

    @Test
    fun incompleteGemmaOutputUsesItsTwoSharedStructuredCriteria() = runBlocking {
        val dataSource = FakeWineReviewDataSource(exactResults = listOf(REVIEWS.first()))
        val responder = KaggleConversationResponder(
            gemmaResponder = singleIncompleteOptionGemmaResponder(),
            wineReviewRepository = dataSource,
        )

        val response = responder.replyTo("earthy red for mushroom risotto")

        assertEquals(
            listOf(WineReviewCriteria(province = "Piedmont", variety = "Nebbiolo")),
            dataSource.criteriaLookups,
        )
        assertEquals(null, dataSource.keywordLookup)
        assertEquals("Italy", response.suggestions.single().country)
        assertEquals("Piedmont", response.suggestions.single().province)
        assertEquals("Nebbiolo", response.suggestions.single().variety)
    }

    @Test
    fun exactKaggleMissTriesTheOriginalRequestBeforeExpandedGemmaCriteria() = runBlocking {
        val dataSource = FakeWineReviewDataSource(keywordResults = listOf(REVIEWS.first()))
        val responder = KaggleConversationResponder(
            gemmaResponder = invalidNamedOptionGemmaResponder(),
            wineReviewRepository = dataSource,
        )

        val response = responder.replyTo("A structured red wine")

        assertEquals(
            listOf(Triple("Italy", "Piedmont", "Nebbiolo")),
            dataSource.exactLookups,
        )
        assertEquals("A structured red wine", dataSource.keywordLookup)
        assertEquals("Wine One", response.suggestions.single().name)
    }

    @Test
    fun kaggleMissPrefersCachedResultOverConcurrentLiveWeb() = runBlocking {
        var webWasCalled = false
        val cache = WineOptionCache { country, province, variety ->
            CachedWineOption(
                name = "Cached Barolo",
                winery = "Cached Winery",
                country = country,
                province = province,
                variety = variety,
                body = "Full",
                tannin = "High",
                acidity = "High",
                flavorNotes = listOf("rose", "tar"),
                suggestedPairing = "Braised beef",
                webSummary = "Saved from an earlier web search.",
            )
        }
        // Cache and web now run at the same time, so even a live result that comes back should
        // lose to the cached one rather than either overwriting shared state out of order.
        val webSearch = WineWebSearchDataSource {
            webWasCalled = true
            listOf(
                WineSuggestion(
                    name = "Online Barolo",
                    winery = "Online Winery",
                    country = "Italy",
                    province = "Piedmont",
                ),
            )
        }
        val responder = KaggleConversationResponder(
            gemmaResponder = invalidNamedOptionGemmaResponder(),
            wineReviewRepository = FakeWineReviewDataSource(),
            optionCache = cache,
            webSearch = webSearch,
        )

        val response = responder.replyTo("Barolo")

        assertEquals("Cached Barolo", response.suggestions.single().name)
        assertEquals("Saved from an earlier web search.", response.suggestions.single().webSummary)
        assertEquals(WineSuggestionSource.CACHE, response.suggestions.single().source)
        assertTrue(webWasCalled)
    }

    @Test
    fun fullerKaggleResultReplacesAValidSingleGemmaCard() = runBlocking {
        val responder = KaggleConversationResponder(
            gemmaResponder = ConversationResponder { gemmaMessage(listOf(GEMMA_OPTIONS.first())) },
            wineReviewRepository = FakeWineReviewDataSource(exactResults = REVIEWS),
        )

        val response = responder.replyTo("A red wine from Italy")

        assertEquals(listOf("Wine One", "Wine Two"), response.suggestions.map { it.name })
        assertTrue(response.suggestions.all { it.source == WineSuggestionSource.KAGGLE })
    }

    @Test
    fun kaggleAndCacheMissQueryWebLast() = runBlocking {
        var webRequest: WineWebSearchRequest? = null
        var savedOption: CachedWineOption? = null
        val cache = object : WineOptionCache {
            override suspend fun find(
                country: String,
                province: String,
                variety: String,
            ): CachedWineOption? = null

            override suspend fun save(option: CachedWineOption) {
                savedOption = option
            }
        }
        val webOption = WineSuggestion(
            name = "Web Wine",
            winery = "Web Winery",
            country = "Italy",
            province = "Piedmont",
            variety = "Nebbiolo",
            webSummary = "A concise web summary.",
        )
        val responder = KaggleConversationResponder(
            gemmaResponder = invalidNamedOptionGemmaResponder(),
            wineReviewRepository = FakeWineReviewDataSource(),
            optionCache = cache,
            webSearch = WineWebSearchDataSource { request ->
                webRequest = request
                listOf(webOption)
            },
        )

        val response = responder.replyTo("Barolo")

        assertEquals("Barolo", webRequest?.originalQuery)
        assertEquals("Web Wine", response.suggestions.single().name)
        assertEquals(WineSuggestionSource.WEB_SEARCH, response.suggestions.single().source)
        assertEquals("Web Wine", savedOption?.name)
    }

    @Test
    fun webResultAlreadyCoveredByKaggleIsNotCachedAgain() = runBlocking {
        var saveWasCalled = false
        val cache = object : WineOptionCache {
            override suspend fun find(country: String, province: String, variety: String): CachedWineOption? = null
            override suspend fun save(option: CachedWineOption) {
                saveWasCalled = true
            }
        }
        // Kaggle's own structured/keyword search comes up empty (so the flow genuinely reaches
        // web), but the web result's exact country/province/variety triple turns out to already
        // be covered by Kaggle — that combination should not be duplicated into the cache.
        val dataSource = object : WineReviewDataSource {
            override suspend fun find(criteria: WineReviewCriteria): List<WineReview> = emptyList()
            override suspend fun findByKeywords(userQuery: String): List<WineReview> = emptyList()
            override suspend fun findExact(
                country: String,
                province: String,
                variety: String,
            ): List<WineReview> =
                if (country == "Italy" && province == "Piedmont" && variety == "Nebbiolo") REVIEWS else emptyList()
        }
        val webOption = WineSuggestion(
            name = "Web Wine",
            winery = "Web Winery",
            country = "Italy",
            province = "Piedmont",
            variety = "Nebbiolo",
            webSummary = "A concise web summary.",
        )
        val responder = KaggleConversationResponder(
            gemmaResponder = invalidNamedOptionGemmaResponder(),
            wineReviewRepository = dataSource,
            optionCache = cache,
            webSearch = WineWebSearchDataSource { listOf(webOption) },
        )

        val response = responder.replyTo("Barolo")

        assertEquals("Web Wine", response.suggestions.single().name)
        assertEquals(WineSuggestionSource.WEB_SEARCH, response.suggestions.single().source)
        assertTrue(!saveWasCalled)
    }

    @Test
    fun webResultNotMatchingTheRecordedContextIsNotCachedEvenIfFullyResolved() = runBlocking {
        // Recorded preferences: red wine from France. Kaggle/cache/Gemma all miss, so the
        // follow-up web search runs — but the live result it returns is a fully-resolved wine
        // from Italy, not France. Every field on that result is "resolved" (not "Unknown"), but
        // it doesn't match the context Kotlin actually recorded, so it must not be cached.
        var saveWasCalled = false
        val cache = object : WineOptionCache {
            override suspend fun find(country: String, province: String, variety: String): CachedWineOption? = null
            override suspend fun save(option: CachedWineOption) {
                saveWasCalled = true
            }
        }
        val recordedPreferences = WinePreferences(type = "red", country = "France")
        val gemma = FakeCardSynthesizingGemma(cards = emptyList(), preferences = recordedPreferences)
        val webOption = WineSuggestion(
            name = "Barolo",
            winery = "Some Winery",
            country = "Italy",
            province = "Piedmont",
            variety = "Nebbiolo",
        )
        val responder = KaggleConversationResponder(
            gemmaResponder = gemma,
            wineReviewRepository = FakeWineReviewDataSource(),
            optionCache = cache,
            webSearch = WineWebSearchDataSource { listOf(webOption) },
        )

        responder.replyTo("Full body")
        val webResponse = responder.replyTo("yes")

        assertEquals("Barolo", webResponse.suggestions.single().name)
        assertTrue(!saveWasCalled)
    }

    @Test
    fun webResultMatchesViaPhraseEvidenceWhenTheStructuredFieldIsUnresolved() = runBlocking {
        // Recorded preferences: Italian wine, full body. The live web result's own `body` field
        // wasn't cleanly synthesized ("Unknown"), but its summary text uses sommelier synonyms
        // ("bold", "rich", "opulent") for Full-Bodied — that phrase evidence should count as a
        // match just as much as an exact "Full-Bodied" field value would.
        var savedOption: CachedWineOption? = null
        val cache = object : WineOptionCache {
            override suspend fun find(country: String, province: String, variety: String): CachedWineOption? = null
            override suspend fun save(option: CachedWineOption) {
                savedOption = option
            }
        }
        val recordedPreferences = WinePreferences(type = "red", country = "Italy", body = "Full-Bodied")
        val gemma = FakeCardSynthesizingGemma(cards = emptyList(), preferences = recordedPreferences)
        val webOption = WineSuggestion(
            name = "Barolo",
            winery = "Some Winery",
            country = "Italy",
            province = "Piedmont",
            variety = "Nebbiolo",
            webSummary = "A bold, rich, opulent wine with great structure.",
        )
        val responder = KaggleConversationResponder(
            gemmaResponder = gemma,
            wineReviewRepository = FakeWineReviewDataSource(),
            optionCache = cache,
            webSearch = WineWebSearchDataSource { listOf(webOption) },
        )

        responder.replyTo("Full body")
        val webResponse = responder.replyTo("yes")

        assertEquals("Barolo", webResponse.suggestions.single().name)
        assertEquals("Barolo", savedOption?.name)
    }

    private fun twoOptionGemmaResponder(): ConversationResponder = ConversationResponder {
        gemmaMessage(GEMMA_OPTIONS)
    }

    private fun invalidNamedOptionGemmaResponder(): ConversationResponder = ConversationResponder {
        gemmaMessage(listOf(GEMMA_OPTIONS.first().copy(name = "Unknown")))
    }

    private fun singleIncompleteOptionGemmaResponder(): ConversationResponder = ConversationResponder {
        gemmaMessage(listOf(GEMMA_OPTIONS.first().copy(country = "Unknown")))
    }

    private fun gemmaMessage(
        options: List<WineSuggestion>,
        coverageComplete: Boolean = false,
    ) = ChatMessage(
        id = 1L,
        author = MessageAuthor.Assistant,
        text = "Gemma response",
        suggestion = options.firstOrNull(),
        suggestions = options,
        stageOneOutput = true,
        coverageComplete = coverageComplete,
    )

    private fun clarificationMessage(text: String) = ChatMessage(
        id = 1L,
        author = MessageAuthor.Assistant,
        text = text,
        needsClarification = true,
    )

    /**
     * Simulates the real GemmaConversationResponder's split: the turn that completes Q1-Q3
     * hands back [resolvedPreferences] without calling Gemma, and [synthesizeCards] is a
     * separate call a caller can run concurrently with its own lookup.
     */
    private inner class FakeCardSynthesizingGemma(
        private val cards: List<WineSuggestion>,
        private val preferences: WinePreferences,
        private val synthesisDelayMs: Long = 0,
    ) : ConversationResponder, WineCardSynthesizer {
        var receivedPreferences: WinePreferences? = null

        override suspend fun replyTo(query: String): ChatMessage = replyToUpdates(query) {}

        override suspend fun replyToUpdates(
            query: String,
            onUpdate: (ConversationStreamUpdate) -> Unit,
        ): ChatMessage = ChatMessage(
            id = 1L,
            author = MessageAuthor.Assistant,
            text = "",
            coverageComplete = true,
            resolvedPreferences = preferences,
        )

        override suspend fun synthesizeCards(
            preferences: WinePreferences,
            onUpdate: (ConversationStreamUpdate) -> Unit,
        ): ChatMessage {
            if (synthesisDelayMs > 0) kotlinx.coroutines.delay(synthesisDelayMs)
            receivedPreferences = preferences
            return gemmaMessage(cards, coverageComplete = true)
        }
    }

    private class FakeWineReviewDataSource(
        private val exactResults: List<WineReview> = emptyList(),
        private val keywordResults: List<WineReview> = emptyList(),
        private val selectionPoolResults: List<WineReview> = emptyList(),
    ) : WineReviewDataSource {
        val criteriaLookups = mutableListOf<WineReviewCriteria>()
        val exactLookups: List<Triple<String, String, String>>
            get() = criteriaLookups.mapNotNull { criteria ->
                val country = criteria.country ?: return@mapNotNull null
                val province = criteria.province ?: return@mapNotNull null
                val variety = criteria.variety ?: return@mapNotNull null
                Triple(country, province, variety)
            }
        var keywordLookup: String? = null
        val selectionPoolLookups = mutableListOf<WineSelectionCriteria>()

        override suspend fun find(criteria: WineReviewCriteria): List<WineReview> {
            criteriaLookups += criteria
            return exactResults
        }

        override suspend fun findByKeywords(userQuery: String): List<WineReview> {
            keywordLookup = userQuery
            return keywordResults
        }

        override suspend fun findBySelectionPool(
            criteria: WineSelectionCriteria,
        ): List<WineReview> {
            selectionPoolLookups += criteria
            return selectionPoolResults
        }
    }

    private companion object {
        val GEMMA_OPTIONS = listOf(
            WineSuggestion(
                name = "Barolo",
                winery = "Unknown",
                country = "Italy",
                province = "Piedmont",
                variety = "Nebbiolo",
            ),
            WineSuggestion(
                name = "Brunello",
                winery = "Unknown",
                country = "Italy",
                province = "Tuscany",
                variety = "Sangiovese",
            ),
            WineSuggestion(
                name = "Rioja",
                winery = "Unknown",
                country = "Spain",
                province = "Rioja",
                variety = "Tempranillo",
            ),
        )
        val REVIEWS = listOf(
            WineReview(
                id = 1,
                name = "Wine One",
                winery = "Winery A",
                country = "Italy",
                province = "Piedmont",
                variety = "Nebbiolo",
                points = 99,
                reviewSummary = "Review 1: Full text || Review 2: More text",
            ),
            WineReview(
                id = 2,
                name = "Wine Two",
                winery = "Winery B",
                country = "Italy",
                province = "Piedmont",
                variety = "Nebbiolo",
                points = 97,
                reviewSummary = "Another complete review",
            ),
        )
    }
}
