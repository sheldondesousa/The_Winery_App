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
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import com.sheldondesousa.uncork.ui.conversation.wineSuggestions
import kotlinx.coroutines.CancellationException
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
            val completedSuggestions = gemmaResponse.wineSuggestions.take(TARGET_OPTION_COUNT)
            logFlow("Onboarding coverage complete; starting Kaggle lookup")
            return@withLock findKaggleThenWeb(
                originalQuery = fullRequest,
                gemmaSuggestions = completedSuggestions,
            )
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

    private suspend fun findKaggleThenWeb(
        originalQuery: String,
        gemmaSuggestions: List<WineSuggestion>,
        gemmaFallback: List<WineSuggestion> = emptyList(),
        gemmaFallbackText: String = "I found these options for you.",
    ): ChatMessage {
        val selectionCriteria = buildSelectionCriteria(originalQuery, gemmaSuggestions)
        val kaggleOptions = runCatchingSource {
            findKaggleOptions(originalQuery, gemmaSuggestions, selectionCriteria)
        }
        logFlow("Kaggle cards=${kaggleOptions.size}")
        if (kaggleOptions.isNotEmpty()) {
            return successfulOptions(
                text = "I searched my database and found these matches from other wine enthusiasts.",
                options = kaggleOptions,
                originalQuery = originalQuery,
                followUpText = WEB_SEARCH_QUESTION,
                nextSource = NextSource.WEB,
                gemmaSuggestions = gemmaSuggestions,
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
            return searchWeb(originalQuery, gemmaSuggestions)
        }

        val cachedOptions = runCatchingSource {
            findCachedOptions(originalQuery, gemmaSuggestions)
        }
        logFlow("Cache cards=${cachedOptions.size}")
        if (cachedOptions.isNotEmpty()) {
            return successfulOptions(
                text = "I found ${if (cachedOptions.size == 1) "an option" else "some options"} " +
                    "saved from an earlier web search.",
                options = cachedOptions,
                originalQuery = originalQuery,
            )
        }

        val webResponse = searchWeb(originalQuery, gemmaSuggestions, returnFailure = false)
        if (webResponse.wineSuggestions.isNotEmpty()) return webResponse

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
    ): ChatMessage {
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
            webOptions.firstOrNull { it.hasExactSearchCriteria() }
                ?.toCachedWineOption()
                ?.let { option -> runCatchingCacheWrite { optionCache.save(option) } }
        }
        if (webOptions.isNotEmpty()) {
            return successfulOptions(
                text = "I searched online and found these options for you.",
                options = webOptions,
                originalQuery = originalQuery,
            )
        }
        return if (returnFailure) noResultsMessage(originalQuery) else assistantMessage("", historyRequest = originalQuery)
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

    private fun WineSuggestion.hasRequiredCardFields(): Boolean =
        name.isResolved() && country.isResolved()

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
        source = WineSuggestionSource.WEB_SEARCH,
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
    ): ChatMessage {
        val contextualOptions = options.map { option ->
            option.copy(requestContext = option.requestContext ?: originalQuery)
        }
        shownCardKeys += contextualOptions.map { it.cardKey() }
        pendingNextSource = nextSource?.let {
            PendingNextSource(originalQuery, gemmaSuggestions, it)
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
    )

    private companion object {
        const val TARGET_OPTION_COUNT = 3
        const val MAX_FALLBACK_OPTIONS = 3
        const val ENTHUSIAST_QUESTION =
            "Would you like me to check for suggestions from wine enthusiasts in the database?"
        const val WEB_SEARCH_QUESTION = "Would you like me to run a broader web search?"

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
