package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.data.profile.CachedWineOption
import com.sheldondesousa.uncork.data.profile.WineOptionCache
import com.sheldondesousa.uncork.data.reviews.WineReview
import com.sheldondesousa.uncork.data.reviews.WineReviewCriteria
import com.sheldondesousa.uncork.data.reviews.WineReviewDataSource
import com.sheldondesousa.uncork.data.reviews.WineSelectionCriteria
import com.sheldondesousa.uncork.ui.conversation.ChatMessage
import com.sheldondesousa.uncork.ui.conversation.ConversationStreamUpdate
import com.sheldondesousa.uncork.ui.conversation.ConversationResponder
import com.sheldondesousa.uncork.ui.conversation.MessageAuthor
import com.sheldondesousa.uncork.ui.conversation.SourceQueryStatus
import com.sheldondesousa.uncork.ui.conversation.SourceResult
import com.sheldondesousa.uncork.ui.conversation.WineCardSynthesizer
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import com.sheldondesousa.uncork.ui.conversation.wineSuggestions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer

data class WineWebSearchRequest(
    val originalQuery: String,
    val gemmaSuggestions: List<WineSuggestion>,
    val limit: Int = 3,
)

fun interface WineWebSearchDataSource {
    suspend fun search(request: WineWebSearchRequest): List<WineSuggestion>
}

object UnavailableWineWebSearchDataSource : WineWebSearchDataSource {
    override suspend fun search(request: WineWebSearchRequest): List<WineSuggestion> = emptyList()
}

class KaggleConversationResponder(
    private val gemmaResponder: ConversationResponder,
    private val wineReviewRepository: WineReviewDataSource,
    private val optionCache: WineOptionCache = WineOptionCache { _, _, _ -> null },
    private val webSearch: WineWebSearchDataSource = UnavailableWineWebSearchDataSource,
) : ConversationResponder {
    private val responseMutex = Mutex()
    private var pendingNextSource: PendingNextSource? = null
    private val shownCardKeys = mutableSetOf<String>()
    private val clarificationInputs = mutableListOf<String>()

    override suspend fun replyTo(query: String): ChatMessage =
        replyToUpdates(query) {}

    override suspend fun replyToStreaming(
        query: String,
        onPartialText: (String) -> Unit,
    ): ChatMessage = replyToUpdates(query) { update -> onPartialText(update.text) }

    override suspend fun replyToUpdates(
        query: String,
        onUpdate: (ConversationStreamUpdate) -> Unit,
    ): ChatMessage = responseMutex.withLock {
        val pending = pendingNextSource
        val requestsMore = pending != null && (query.isAffirmativeReply() || query.requestsMoreOptions())
        if (pending != null && query.isNegativeReply()) {
            pendingNextSource = null
            return@withLock assistantMessage(
                text = "Of course — we’ll keep those recommendations.",
                historyRequest = pending.originalQuery,
            )
        }
        if (pending != null && requestsMore) {
            pendingNextSource = null
            return@withLock when (pending.nextSource) {
                NextSource.KAGGLE -> findKaggleThenWeb(
                    originalQuery = pending.originalQuery,
                    gemmaSuggestions = pending.gemmaSuggestions,
                )
                NextSource.WEB -> searchWeb(
                    originalQuery = pending.originalQuery,
                    gemmaSuggestions = pending.gemmaSuggestions,
                    criteria = pending.criteria,
                )
            }
        }
        if (pending != null && !requestsMore) {
            pendingNextSource = null
            shownCardKeys.clear()
        }

        val queryAddsSearchContext =
            !query.isNonDirectionalReply() && !query.isAmbiguousClarificationReply()
        val fullRequest = (clarificationInputs + listOfNotNull(query.takeIf { queryAddsSearchContext }))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(". ")
            .ifBlank { "No preference" }

        val gemmaResponse = try {
            gemmaResponder.replyToUpdates(query) { update ->
                onUpdate(
                    update.copy(
                        suggestions = update.suggestions
                            .filter { it.hasRequiredCardFields() }
                            .filterNot { it.cardKey() in shownCardKeys }
                            .distinctBy { it.cardKey() }
                            .take(TARGET_OPTION_COUNT),
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return@withLock fallbackThroughSources(fullRequest, emptyList())
        }

        if (gemmaResponse.coverageComplete) {
            clarificationInputs.clear()
            val preferences = gemmaResponse.resolvedPreferences
            val synthesizer = gemmaResponder as? WineCardSynthesizer
            return@withLock if (preferences != null && synthesizer != null) {
                logFlow("Onboarding coverage complete; querying Kaggle from recorded preferences")
                findPreferencesDrivenResult(fullRequest, preferences, synthesizer, onUpdate)
            } else {
                logFlow("Onboarding coverage complete; starting Kaggle lookup")
                findKaggleThenWeb(
                    originalQuery = fullRequest,
                    gemmaSuggestions = gemmaResponse.wineSuggestions.take(TARGET_OPTION_COUNT),
                )
            }
        }

        if (!gemmaResponse.stageOneOutput && !requestsMore) {
            if (queryAddsSearchContext) clarificationInputs += query
            return@withLock gemmaResponse.copy(
                suggestion = null,
                suggestions = emptyList(),
                historyRequest = fullRequest,
            )
        }

        clarificationInputs.clear()
        val gemmaOptions = gemmaResponse.wineSuggestions
            .filter { it.hasRequiredCardFields() }
            .filterNot { it.cardKey() in shownCardKeys }
            .distinctBy { it.cardKey() }
            .take(TARGET_OPTION_COUNT)
        logFlow(
            "Gemma cards parsed=${gemmaResponse.wineSuggestions.size}, usable=${gemmaOptions.size}",
        )

        if (
            gemmaOptions.size == TARGET_OPTION_COUNT ||
            (gemmaResponse.discardedStageOneCards > 0 && gemmaOptions.isNotEmpty())
        ) {
            return@withLock successfulOptions(
                text = gemmaResponse.text.trim().ifBlank { "I found these options for you." },
                options = gemmaOptions,
                originalQuery = fullRequest,
                followUpText = ENTHUSIAST_QUESTION,
                nextSource = NextSource.KAGGLE,
                gemmaSuggestions = gemmaResponse.wineSuggestions,
            )
        }

        if (gemmaResponse.discardedStageOneCards > 0) {
            return@withLock gemmaResponse.copy(
                suggestion = null,
                suggestions = emptyList(),
                historyRequest = fullRequest,
            )
        }

        fallbackThroughSources(
            originalQuery = fullRequest,
            gemmaSuggestions = gemmaResponse.wineSuggestions.take(TARGET_OPTION_COUNT),
            initialOptions = gemmaOptions,
            initialText = gemmaResponse.text.trim().ifBlank { "I found these options for you." },
        )
    }

    private suspend fun fallbackThroughSources(
        originalQuery: String,
        gemmaSuggestions: List<WineSuggestion>,
        initialOptions: List<WineSuggestion> = emptyList(),
        initialText: String = "I found these options for you.",
    ): ChatMessage = findKaggleThenWeb(
        originalQuery = originalQuery,
        gemmaSuggestions = gemmaSuggestions,
        gemmaFallback = initialOptions,
        gemmaFallbackText = initialText,
    )

    /**
     * The Q1-Q3 flow just finished, so Kotlin already has the resolved [WinePreferences]. Mirrors
     * Find's own workflow: Gemma's card synthesis and Kaggle run concurrently from the start,
     * like Find's "AI Sommelier" and "Database" sections (they never contend for a resource —
     * Kaggle is a plain local DB query; it's Gemma's own *web*-synthesis call, not card
     * synthesis, that shares the on-device engine mutex with card synthesis). Once Kaggle
     * finishes, the cache is queried too. Web search is never run automatically here — it's only
     * offered as a follow-up question, same as the "Would you like me to run a broader web
     * search?" prompt this app has always shown; accepting it runs [searchWeb] via the existing
     * [PendingNextSource] handling in [replyToUpdates], which already saves an exact-match live
     * hit into the cache in the background (see [findWebOptions]).
     */
    private suspend fun findPreferencesDrivenResult(
        originalQuery: String,
        preferences: WinePreferences,
        synthesizer: WineCardSynthesizer,
        onUpdate: (ConversationStreamUpdate) -> Unit,
    ): ChatMessage {
        val criteria = preferences.toSelectionCriteria()
        val basis = preferences.toBasisSuggestion()
        val progress = SourceProgress(onUpdate)

        progress.startLoading(WineSuggestionSource.GEMMA, WineSuggestionSource.KAGGLE)
        val (gemmaSuggestions, kaggleOptions) = coroutineScope {
            val gemmaDeferred = async {
                val cardsResponse = try {
                    synthesizer.synthesizeCards(preferences) {}
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                }
                val suggestions = cardsResponse?.wineSuggestions.orEmpty()
                    .filter { it.hasRequiredCardFields() }
                    .filterNot { it.cardKey() in shownCardKeys }
                    .distinctBy { it.cardKey() }
                    .take(TARGET_OPTION_COUNT)
                progress.resolve(WineSuggestionSource.GEMMA, suggestions)
                suggestions
            }
            val kaggleDeferred = async {
                val reviews = DebugLatencyLog.timed("[Kotlin] Kaggle DB query (recorded preferences)") {
                    runCatchingSource { findKaggleReviewsByCriteria(criteria) }
                }
                val options = reviews
                    .distinctBy { it.id }
                    .map { review -> review.toSuggestion(basis) }
                    .filter { it.hasRequiredCardFields() }
                    .filterNot { it.cardKey() in shownCardKeys }
                    .distinctBy { it.cardKey() }
                    .take(MAX_FALLBACK_OPTIONS)
                progress.resolve(WineSuggestionSource.KAGGLE, options)
                options
            }
            gemmaDeferred.await() to kaggleDeferred.await()
        }
        logFlow(
            "From recorded preferences: Gemma cards=${gemmaSuggestions.size}, " +
                "Kaggle reviews=${kaggleOptions.size}",
        )

        // Kaggle is complete — query the cache next, same as it always has.
        progress.startLoading(WineSuggestionSource.CACHE)
        val cachedOptions = DebugLatencyLog.timed("[Kotlin] local cache lookup (recorded preferences)") {
            runCatchingSource { findCachedOptionsByCriteria(criteria) }
        }
        logFlow("Cache options from recorded preferences=${cachedOptions.size}")
        progress.resolve(WineSuggestionSource.CACHE, cachedOptions)

        return buildMultiSourceResponse(
            originalQuery = originalQuery,
            criteria = criteria,
            sourceResults = progress.results,
            webFollowUpSuggestions = gemmaSuggestions.ifEmpty { listOf(basis) },
        )
    }

    /**
     * Publishes [SourceResult]s to the UI as each source resolves, in resolution order, with
     * every not-yet-resolved source shown as [SourceQueryStatus.LOADING] until it does. A source
     * that resolves with zero suggestions still gets a [SourceQueryStatus.COMPLETE] entry (an
     * empty list) so "No results found" can be shown rather than the source silently vanishing.
     */
    private class SourceProgress(private val onUpdate: (ConversationStreamUpdate) -> Unit) {
        private val lock = Mutex()
        private val resolved = mutableListOf<SourceResult>()
        private var loading = listOf<WineSuggestionSource>()

        val results: List<SourceResult> get() = resolved.toList()

        suspend fun startLoading(vararg sources: WineSuggestionSource) {
            lock.withLock { loading = loading + sources }
            publish()
        }

        suspend fun resolve(source: WineSuggestionSource, suggestions: List<WineSuggestion>) {
            lock.withLock {
                resolved += SourceResult(source, SourceQueryStatus.COMPLETE, suggestions)
                loading = loading - source
            }
            publish()
        }

        private fun publish() {
            onUpdate(
                ConversationStreamUpdate(
                    text = "",
                    sourceResults = resolved + loading.map { SourceResult(it, SourceQueryStatus.LOADING) },
                ),
            )
        }
    }

    private fun buildMultiSourceResponse(
        originalQuery: String,
        criteria: WineSelectionCriteria,
        sourceResults: List<SourceResult>,
        webFollowUpSuggestions: List<WineSuggestion>,
    ): ChatMessage {
        // sourceResults is in actual resolution order, for the progressive per-source display.
        // The single "winning" text/suggestion, by contrast, keeps "database first" priority
        // regardless of which one happened to resolve first — Kaggle and Gemma run as a genuine
        // race, and which one resolves first shouldn't flip which text/card the rest of the app
        // (favorites, the staged profile view) treats as the primary result.
        val firstHit = DISPLAY_PRIORITY
            .firstNotNullOfOrNull { source -> sourceResults.firstOrNull { it.source == source && it.suggestions.isNotEmpty() } }
        val contextualResults = sourceResults.map { result ->
            result.copy(
                suggestions = result.suggestions.map { option ->
                    option.copy(requestContext = option.requestContext ?: originalQuery)
                },
            )
        }
        shownCardKeys += contextualResults.flatMap { it.suggestions }.map { it.cardKey() }

        val text = when (firstHit?.source) {
            null -> "I’m sorry, but I couldn’t find a relevant wine for that request."
            WineSuggestionSource.KAGGLE -> kaggleResultText(firstHit.suggestions, criteria)
            WineSuggestionSource.CACHE -> "I found ${
                if (firstHit.suggestions.size == 1) "an option" else "some options"
            } saved from an earlier web search."
            WineSuggestionSource.WEB_SEARCH -> "I searched online and found these options for you."
            WineSuggestionSource.GEMMA -> "I found these options for you."
        }
        val winningOptions = contextualResults.firstOrNull { it.suggestions.isNotEmpty() }?.suggestions.orEmpty()

        // Kaggle/cache/Gemma results (or their absence) don't preclude checking online too —
        // always offer the same opt-in "broader web search" this app has always offered, rather
        // than running it automatically.
        pendingNextSource = PendingNextSource(originalQuery, webFollowUpSuggestions, NextSource.WEB, criteria)

        return ChatMessage(
            id = System.nanoTime(),
            author = MessageAuthor.Assistant,
            text = text,
            suggestion = winningOptions.firstOrNull(),
            suggestions = winningOptions,
            sourceResults = contextualResults,
            historyRequest = originalQuery,
            followUpText = WEB_SEARCH_QUESTION,
        )
    }

    private suspend fun findKaggleReviewsByCriteria(criteria: WineSelectionCriteria): List<WineReview> =
        if (criteria.hasAnyValue) wineReviewRepository.findBySelectionPool(criteria) else emptyList()

    private suspend fun findCachedOptionsByCriteria(criteria: WineSelectionCriteria): List<WineSuggestion> {
        if (!criteria.hasAnyValue) return emptyList()
        return optionCache.findMatching(criteria).map { it.toSuggestion() }
            .filter { it.hasRequiredCardFields() }
            .filterNot { it.cardKey() in shownCardKeys }
            .distinctBy { it.cardKey() }
            .take(MAX_FALLBACK_OPTIONS)
    }

    private suspend fun findKaggleThenWeb(
        originalQuery: String,
        gemmaSuggestions: List<WineSuggestion>,
        gemmaFallback: List<WineSuggestion> = emptyList(),
        gemmaFallbackText: String = "I found these options for you.",
    ): ChatMessage {
        val selectionCriteria = buildSelectionCriteria(originalQuery, gemmaSuggestions)
        val kaggleOptions = DebugLatencyLog.timed("[Kotlin] Kaggle DB query") {
            runCatchingSource {
                findKaggleOptions(originalQuery, gemmaSuggestions, selectionCriteria)
            }
        }
        logFlow("Kaggle cards=${kaggleOptions.size}")
        if (kaggleOptions.isNotEmpty()) {
            return successfulOptions(
                text = kaggleResultText(kaggleOptions, selectionCriteria),
                options = kaggleOptions,
                originalQuery = originalQuery,
                followUpText = WEB_SEARCH_QUESTION,
                nextSource = NextSource.WEB,
                gemmaSuggestions = gemmaSuggestions,
                criteria = selectionCriteria,
            )
        }

        val limitingAttributes = selectionCriteria.appliedAttributes()
        if (limitingAttributes.isNotEmpty()) {
            return assistantMessage(
                text = "I couldn’t find a database match for that combination. " +
                    "The specified ${limitingAttributes.joinToString(" and ")} " +
                    "${if (limitingAttributes.size == 1) "preference appears" else "preferences appear"} " +
                    "to be the limiting factor.",
                historyRequest = originalQuery,
            )
        }

        if (selectionCriteria.hasExactProfile) {
            return DebugLatencyLog.timed("[Kotlin] web search (+Gemma synthesis)") {
                searchWeb(originalQuery, gemmaSuggestions, criteria = selectionCriteria)
            }
        }

        // Cache is local/instant and web is a live external call, but the user asked for these
        // to run together rather than cache-first-then-web: race them and prefer the cache
        // result when both land, matching the priority the sequential order used to encode.
        val (cachedOptions, webOptions) = coroutineScope {
            val cachedDeferred = async {
                DebugLatencyLog.timed("[Kotlin] local cache lookup") {
                    runCatchingSource { findCachedOptions(originalQuery, gemmaSuggestions) }
                }
            }
            val webDeferred = async {
                DebugLatencyLog.timed("[Kotlin] web search (+Gemma synthesis)") {
                    findWebOptions(originalQuery, gemmaSuggestions, selectionCriteria)
                }
            }
            cachedDeferred.await() to webDeferred.await()
        }
        logFlow("Cache cards=${cachedOptions.size}, web cards=${webOptions.size}")

        if (cachedOptions.isNotEmpty()) {
            return successfulOptions(
                text = "I found ${if (cachedOptions.size == 1) "an option" else "some options"} " +
                    "saved from an earlier web search.",
                options = cachedOptions,
                originalQuery = originalQuery,
            )
        }

        if (webOptions.isNotEmpty()) {
            return successfulOptions(
                text = "I searched online and found these options for you.",
                options = webOptions,
                originalQuery = originalQuery,
            )
        }

        return if (gemmaFallback.isNotEmpty()) {
            successfulOptions(gemmaFallbackText, gemmaFallback, originalQuery)
        } else {
            noResultsMessage(originalQuery)
        }
    }

    private suspend fun searchWeb(
        originalQuery: String,
        gemmaSuggestions: List<WineSuggestion>,
        returnFailure: Boolean = true,
        criteria: WineSelectionCriteria = WineSelectionCriteria(),
    ): ChatMessage {
        val webOptions = findWebOptions(originalQuery, gemmaSuggestions, criteria)
        if (webOptions.isNotEmpty()) {
            return successfulOptions(
                text = "I searched online and found these options for you.",
                options = webOptions,
                originalQuery = originalQuery,
            )
        }
        return if (returnFailure) noResultsMessage(originalQuery) else assistantMessage("", historyRequest = originalQuery)
    }

    /**
     * The raw web-search fetch, without building a response — kept separate from [searchWeb] so
     * a caller can race it against another lookup (e.g. the cache) and decide which result wins
     * before committing to [successfulOptions]'s side effects (`shownCardKeys`,
     * `pendingNextSource`). A successful live result is saved to the cache, but only once it
     * actually matches the recorded context (whichever of country/province/variety/body/tannin/
     * acidity Kotlin already knows from Q1-Q3 — not just "are these fields non-'Unknown'") and is
     * confirmed to not already be covered by the Kaggle DB for that country/province/variety.
     */
    private suspend fun findWebOptions(
        originalQuery: String,
        gemmaSuggestions: List<WineSuggestion>,
        criteria: WineSelectionCriteria = WineSelectionCriteria(),
    ): List<WineSuggestion> {
        val webOptions = runCatchingSource {
            webSearch.search(
                WineWebSearchRequest(
                    originalQuery = originalQuery,
                    gemmaSuggestions = gemmaSuggestions,
                ),
            ).take(MAX_FALLBACK_OPTIONS).map { option ->
                option.copy(
                    summary = "Unknown",
                    reviewSummary = "Unknown",
                    source = WineSuggestionSource.WEB_SEARCH,
                )
            }.filter { it.hasRequiredCardFields() }
                .filterNot { it.cardKey() in shownCardKeys }
                .distinctBy { it.cardKey() }
        }
        if (webOptions.isNotEmpty()) {
            webOptions.firstOrNull { option ->
                option.country.isResolved() && option.province.isResolved() && criteria.matches(option)
            }?.let { option ->
                runCatchingCacheWrite {
                    val alreadyInKaggle = wineReviewRepository.findExact(
                        country = option.country,
                        province = option.province,
                        variety = option.variety,
                    ).isNotEmpty()
                    if (!alreadyInKaggle) {
                        optionCache.save(option.toCachedWineOption())
                    }
                }
            }
        }
        return webOptions
    }

    /**
     * "I searched my database and found these matches" implies an exact match on the criteria
     * the user actually specified. When the Kaggle keyword fallback (or any other path) surfaces
     * cards that don't actually satisfy a resolved criterion — e.g. a search for country=China
     * returning Italian/Portuguese/US wines because China has no rows in the dataset — the copy
     * needs to say so instead of implying a genuine database hit.
     */
    private fun kaggleResultText(
        options: List<WineSuggestion>,
        criteria: WineSelectionCriteria,
    ): String = if (options.all { criteria.matches(it) }) {
        "I searched my database and found these matches from other wine enthusiasts."
    } else {
        "While I couldn’t find an exact match, I found some close alternatives that you might like."
    }

    private fun WineSelectionCriteria.matches(option: WineSuggestion): Boolean =
        (wineType == null || option.wineType.equals(wineType, ignoreCase = true)) &&
            (country == null || option.country.equals(country, ignoreCase = true)) &&
            (province == null || option.province.equals(province, ignoreCase = true)) &&
            (variety == null || option.variety.equals(variety, ignoreCase = true)) &&
            (body == null || option.matchesAttribute(body, BODY_KEYWORDS)) &&
            (tannin == null || option.matchesAttribute(tannin, TANNIN_KEYWORDS)) &&
            (acidity == null || option.matchesAttribute(acidity, ACIDITY_KEYWORDS))

    /**
     * True if [expectedLabel] equals the option's own structured value for this attribute (e.g.
     * Kaggle's precomputed `body` column), or — since a web-synthesized card's structured field
     * isn't reliably populated — if the option's free text (review/web summary) contains one of
     * the sommelier synonym phrases for that label (the same vocabulary Chat uses to parse a
     * user's own Q3 answer; see [ChatFlow]'s BODY_KEYWORDS/TANNIN_KEYWORDS/ACIDITY_KEYWORDS).
     */
    private fun WineSuggestion.matchesAttribute(
        expectedLabel: String,
        keywordMap: Map<String, List<String>>,
    ): Boolean {
        val structuredValue = when (keywordMap) {
            BODY_KEYWORDS -> body
            TANNIN_KEYWORDS -> tannin
            ACIDITY_KEYWORDS -> acidity
            else -> "Unknown"
        }
        if (structuredValue.equals(expectedLabel, ignoreCase = true)) return true
        val phrases = keywordMap[expectedLabel].orEmpty()
        if (phrases.isEmpty()) return false
        val freeText = listOf(webSummary, reviewSummary, summary)
            .filter { it.isResolved() }
            .joinToString(" ")
            .lowercase()
        if (freeText.isBlank()) return false
        return phrases.any { phrase -> freeText.contains(phrase.lowercase()) }
    }

    private fun noResultsMessage(originalQuery: String): ChatMessage = assistantMessage(
        text = "I’m sorry, but I couldn’t find a relevant wine for that request.",
        historyRequest = originalQuery,
    )

    private suspend fun findKaggleOptions(
        originalQuery: String,
        gemmaSuggestions: List<WineSuggestion>,
        selectionCriteria: WineSelectionCriteria = buildSelectionCriteria(
            originalQuery,
            gemmaSuggestions,
        ),
    ): List<WineSuggestion> {
        val basis = gemmaSuggestions.firstOrNull() ?: EMPTY_PROFILE
        val poolReviews = if (selectionCriteria.hasAnyValue) {
            wineReviewRepository.findBySelectionPool(selectionCriteria)
                .map { review -> review to basis }
        } else {
            emptyList()
        }
        val searchableProfiles = gemmaSuggestions.filter { it.hasExactSearchCriteria() }
        val commonCriteria = gemmaSuggestions.commonWineReviewCriteria()
        val structuredReviews = poolReviews.ifEmpty {
            when {
                commonCriteria.hasAnyValue -> {
                    wineReviewRepository.find(commonCriteria).map { review -> review to basis }
                }
                else -> searchableProfiles
                    .distinctBy { Triple(it.country, it.province, it.variety) }
                    .flatMap { profile ->
                        wineReviewRepository.findExact(
                            country = profile.country,
                            province = profile.province,
                            variety = profile.variety,
                        ).map { review -> review to profile }
                    }
            }
        }
        val reviews = structuredReviews.ifEmpty {
            val basis = gemmaSuggestions.firstOrNull() ?: EMPTY_PROFILE
            val requestMatches = wineReviewRepository.findByKeywords(originalQuery)
            val keywordMatches = requestMatches.ifEmpty {
                wineReviewRepository.findByKeywords(
                    buildKaggleKeywordCriteria(originalQuery, gemmaSuggestions),
                )
            }
            keywordMatches.map { review -> review to basis }
        }

        return reviews
            .distinctBy { (review, _) -> review.id }
            .map { (review, basis) -> review.toSuggestion(basis) }
            .filter { it.hasRequiredCardFields() }
            .filterNot { it.cardKey() in shownCardKeys }
            .distinctBy { it.cardKey() }
            .take(MAX_FALLBACK_OPTIONS)
    }

    private fun List<WineSuggestion>.commonWineReviewCriteria(): WineReviewCriteria =
        WineReviewCriteria(
            country = commonResolvedValue(WineSuggestion::country),
            province = commonResolvedValue(WineSuggestion::province),
            variety = commonResolvedValue(WineSuggestion::variety),
        )

    private fun List<WineSuggestion>.commonResolvedValue(
        selector: (WineSuggestion) -> String,
    ): String? {
        if (isEmpty()) return null
        val values = map(selector)
        if (values.any { !it.isResolved() }) return null
        return values.first().takeIf { first ->
            values.all { value -> value.equals(first, ignoreCase = true) }
        }
    }

    private fun buildKaggleKeywordCriteria(
        originalQuery: String,
        gemmaSuggestions: List<WineSuggestion>,
    ): String = buildList {
        add(originalQuery)
        gemmaSuggestions.forEach { suggestion ->
            addAll(
                listOf(
                    suggestion.variety,
                    suggestion.province,
                    suggestion.country,
                    suggestion.name,
                    suggestion.winery,
                ).filter { it.isResolved() },
            )
        }
    }.distinctBy { it.trim().lowercase() }.joinToString(" ")

    private suspend fun findCachedOptions(
        originalQuery: String,
        gemmaSuggestions: List<WineSuggestion>,
    ): List<WineSuggestion> {
        val selectionCriteria = buildSelectionCriteria(originalQuery, gemmaSuggestions)
        val matchingOptions = if (selectionCriteria.hasAnyValue) {
            optionCache.findMatching(selectionCriteria).map { it.toSuggestion() }
        } else {
            emptyList()
        }
        val options = matchingOptions.ifEmpty {
            gemmaSuggestions
                .filter { it.hasExactSearchCriteria() }
                .distinctBy { Triple(it.country, it.province, it.variety) }
                .mapNotNull { profile ->
                    optionCache.find(
                        profile.country,
                        profile.province,
                        profile.variety,
                    )?.toSuggestion()
                }
        }
        return options
            .filter { it.hasRequiredCardFields() }
            .filterNot { it.cardKey() in shownCardKeys }
            .distinctBy { it.cardKey() }
            .take(MAX_FALLBACK_OPTIONS)
    }

    internal fun buildSelectionCriteria(
        originalQuery: String,
        gemmaSuggestions: List<WineSuggestion>,
    ): WineSelectionCriteria {
        val normalized = originalQuery.normalizedReply()
        val words = normalized.split(' ').filter(String::isNotBlank).toSet()
        fun containsPhrase(value: String): Boolean {
            val candidate = value.normalizedReply()
            return candidate.isNotBlank() && Regex("(^| )${Regex.escape(candidate)}( |$)")
                .containsMatchIn(normalized)
        }
        fun explicitSuggestionValue(selector: (WineSuggestion) -> String): String? =
            gemmaSuggestions.map(selector)
                .firstOrNull { it.isResolved() && containsPhrase(it) }

        val wineType = PRIMARY_WINE_TYPES.firstOrNull(words::contains)
        val body = when {
            listOf("full bodied", "full body", "bold", "robust", "powerful")
                .any(::containsPhrase) -> "Full"
            listOf("medium bodied", "medium body").any(::containsPhrase) -> "Medium"
            listOf("light bodied", "light body", "light").any(::containsPhrase) -> "Light"
            else -> null
        }
        val tannin = attributeLevel(normalized, "tannin")
        val acidity = attributeLevel(normalized, "acidity")
        val country = explicitSuggestionValue(WineSuggestion::country)
            ?: COUNTRY_ALIASES.entries.firstOrNull { (phrase, _) -> containsPhrase(phrase) }?.value
        return WineSelectionCriteria(
            wineType = wineType,
            country = country,
            province = explicitSuggestionValue(WineSuggestion::province),
            variety = explicitSuggestionValue(WineSuggestion::variety),
            body = body,
            tannin = tannin,
            acidity = acidity,
        )
    }

    private val WineSelectionCriteria.hasExactProfile: Boolean
        get() = !country.isNullOrBlank() && !province.isNullOrBlank() && !variety.isNullOrBlank()

    private fun WineSelectionCriteria.appliedAttributes(): List<String> = buildList {
        body?.let { add("body ($it)") }
        tannin?.let { add("tannin ($it)") }
        acidity?.let { add("acidity ($it)") }
    }

    private fun attributeLevel(normalizedQuery: String, attribute: String): String? {
        val attributeIndex = normalizedQuery.indexOf(attribute)
        if (attributeIndex < 0) return null
        val nearby = normalizedQuery.substring(
            startIndex = maxOf(0, attributeIndex - 24),
            endIndex = minOf(normalizedQuery.length, attributeIndex + attribute.length + 24),
        )
        return when {
            listOf("high", "firm", "grippy", "bright", "crisp").any(nearby::contains) -> "High"
            listOf("medium", "moderate").any(nearby::contains) -> "Medium"
            listOf("low", "soft", "gentle", "mellow").any(nearby::contains) -> "Low"
            else -> null
        }
    }

    private suspend fun <T> runCatchingSource(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        emptyList()
    }

    private suspend fun runCatchingCacheWrite(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // A cache write must not hide usable web results from the current response.
        }
    }

    private fun WineSuggestion.hasExactSearchCriteria(): Boolean =
        country.isResolved() && province.isResolved() && variety.isResolved()

    private fun WineSuggestion.hasRequiredCardFields(): Boolean = name.isResolved()

    private fun String.isResolved(): Boolean =
        isNotBlank() && !equals("Unknown", ignoreCase = true)

    private fun WineSuggestion.cardKey(): String =
        listOf(name, winery, country, province)
            .joinToString("|") { it.trim().lowercase() }

    private fun WineReview.toSuggestion(gemmaSuggestion: WineSuggestion): WineSuggestion =
        gemmaSuggestion.copy(
            name = name,
            winery = winery,
            country = country,
            province = province,
            variety = variety,
            rating = points,
            summary = "Unknown",
            reviewSummary = reviewSummary,
            webSummary = "Unknown",
            source = WineSuggestionSource.KAGGLE,
        )

    private fun CachedWineOption.toSuggestion(): WineSuggestion = WineSuggestion(
        name = name,
        winery = winery,
        country = country,
        province = province,
        variety = variety,
        body = body ?: "Unknown",
        tannin = tannin ?: "Unknown",
        acidity = acidity ?: "Unknown",
        flavorNotes = flavorNotes.takeIf(List<String>::isNotEmpty)?.joinToString(", ") ?: "Unknown",
        suggestedPairing = suggestedPairing ?: "Unknown",
        webSummary = webSummary ?: "Unknown",
        source = WineSuggestionSource.CACHE,
    )

    private fun WineSuggestion.toCachedWineOption(): CachedWineOption = CachedWineOption(
        name = name,
        winery = winery,
        country = country,
        province = province,
        variety = variety,
        body = body,
        tannin = tannin,
        acidity = acidity,
        flavorNotes = flavorNotes.split(',').map { it.trim() }.filter { it.isNotBlank() },
        suggestedPairing = suggestedPairing,
        webSummary = webSummary,
    )

    private fun successfulOptions(
        text: String,
        options: List<WineSuggestion>,
        originalQuery: String,
        followUpText: String? = null,
        nextSource: NextSource? = null,
        gemmaSuggestions: List<WineSuggestion> = emptyList(),
        criteria: WineSelectionCriteria = WineSelectionCriteria(),
    ): ChatMessage {
        val contextualOptions = options.map { option ->
            option.copy(requestContext = option.requestContext ?: originalQuery)
        }
        shownCardKeys += contextualOptions.map { it.cardKey() }
        pendingNextSource = nextSource?.let {
            PendingNextSource(originalQuery, gemmaSuggestions, it, criteria)
        }
        return assistantMessage(
            text = text,
            options = contextualOptions,
            historyRequest = originalQuery,
            followUpText = followUpText,
        )
    }

    private fun assistantMessage(
        text: String,
        options: List<WineSuggestion> = emptyList(),
        historyRequest: String? = null,
        followUpText: String? = null,
    ): ChatMessage = ChatMessage(
        id = System.nanoTime(),
        author = MessageAuthor.Assistant,
        text = text,
        suggestion = options.firstOrNull(),
        suggestions = options,
        historyRequest = historyRequest,
        followUpText = followUpText,
    )

    private fun String.isAffirmativeReply(): Boolean {
        val reply = normalizedReply()
        if (NEGATIVE_REPLIES.any { reply == it || reply.startsWith("$it ") }) return false
        return AFFIRMATIVE_REPLIES.any { reply == it || reply.startsWith("$it ") }
    }

    private fun String.isNegativeReply(): Boolean {
        val reply = normalizedReply()
        return NEGATIVE_REPLIES.any { reply == it || reply.startsWith("$it ") }
    }

    private fun String.requestsMoreOptions(): Boolean =
        MORE_REQUEST_PHRASES.any { phrase -> normalizedReply().contains(phrase) }

    private fun String.isNonDirectionalReply(): Boolean {
        val reply = normalizedReply()
        return NON_DIRECTIONAL_REPLIES.any { phrase ->
            reply == phrase || reply.startsWith("$phrase ")
        }
    }

    private fun String.isAmbiguousClarificationReply(): Boolean {
        val reply = normalizedReply()
        return AMBIGUOUS_CLARIFICATION_REPLIES.any { phrase ->
            reply == phrase || reply.startsWith("$phrase ")
        }
    }

    private fun String.normalizedReply(): String =
        Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private enum class NextSource { KAGGLE, WEB }

    private data class PendingNextSource(
        val originalQuery: String,
        val gemmaSuggestions: List<WineSuggestion>,
        val nextSource: NextSource,
        // The recorded context (Kotlin's Q1-Q3 criteria) a follow-up web search should still be
        // validated against — whichever fields are known must match, not just be non-"Unknown".
        val criteria: WineSelectionCriteria = WineSelectionCriteria(),
    )

    private companion object {
        const val TARGET_OPTION_COUNT = 3
        const val MAX_FALLBACK_OPTIONS = 3
        const val ENTHUSIAST_QUESTION =
            "Would you like me to check for suggestions from wine enthusiasts in the database?"
        const val WEB_SEARCH_QUESTION = "Would you like me to run a broader web search?"
        val DISPLAY_PRIORITY = listOf(
            WineSuggestionSource.KAGGLE,
            WineSuggestionSource.CACHE,
            WineSuggestionSource.GEMMA,
            WineSuggestionSource.WEB_SEARCH,
        )

        val EMPTY_PROFILE = WineSuggestion(name = "Unknown", province = "Unknown")
        val AFFIRMATIVE_REPLIES = setOf(
            "yes", "yep", "yup", "yeah", "sure", "okay", "ok", "absolutely", "certainly",
            "definitely", "of course", "go ahead", "sounds good", "why not", "please", "please do",
            "do it", "show me", "i would", "i d like that", "id like that", "i would love to",
        )
        val NEGATIVE_REPLIES = setOf(
            "no", "nope", "nah", "no thanks", "no thank you", "not now", "not really",
            "absolutely not", "definitely not", "i m good", "im good",
        )
        val MORE_REQUEST_PHRASES = setOf("more", "more options", "other options", "different options")
        val PRIMARY_WINE_TYPES = setOf("red", "rose", "white", "sparkling", "sweet", "fortified")
        val COUNTRY_ALIASES = linkedMapOf(
            "france" to "France", "french" to "France",
            "italy" to "Italy", "italian" to "Italy",
            "spain" to "Spain", "spanish" to "Spain",
            "portugal" to "Portugal", "portuguese" to "Portugal",
            "argentina" to "Argentina", "argentine" to "Argentina",
            "argentinian" to "Argentina", "chile" to "Chile", "chilean" to "Chile",
            "australia" to "Australia", "australian" to "Australia",
            "new zealand" to "New Zealand", "south africa" to "South Africa",
            "united states" to "US", "american" to "US", "usa" to "US",
            "germany" to "Germany", "german" to "Germany",
            "austria" to "Austria", "austrian" to "Austria",
            "greece" to "Greece", "greek" to "Greece",
        )
        val NON_DIRECTIONAL_REPLIES = setOf(
            "maybe", "not sure", "i m not sure", "im not sure", "i don t know", "i dont know",
            "don t know", "dont know", "unsure", "uncertain", "undecided", "no idea", "no clue",
            "can t decide", "cant decide", "help me", "can you explain", "yes", "yes please",
            "please explain", "idk", "dunno", "whatever", "either", "both", "anything",
            "open to anything", "surprise me", "choose for me", "pick for me",
        )
        val AMBIGUOUS_CLARIFICATION_REPLIES = setOf(
            "def",
        )
    }
}
