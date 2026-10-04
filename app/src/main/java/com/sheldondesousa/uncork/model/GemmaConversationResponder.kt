package com.sheldondesousa.uncork.model

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.sheldondesousa.uncork.data.reviews.FindPhraseEvidence
import com.sheldondesousa.uncork.ui.conversation.ChatMessage
import com.sheldondesousa.uncork.ui.conversation.ConversationStreamUpdate
import com.sheldondesousa.uncork.ui.conversation.ConversationResponder
import com.sheldondesousa.uncork.ui.conversation.MessageAuthor
import com.sheldondesousa.uncork.ui.conversation.WineCardSynthesizer
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GemmaConversationResponder(
    context: Context,
    private val modelFile: File,
) : ConversationResponder, WineCardSynthesizer, AutoCloseable {
    private val appContext = context.applicationContext
    private val cacheDirectory = File(context.cacheDir, "litert-lm").apply { mkdirs() }
    private val requestMutex = Mutex()
    private var engine: Engine? = null
    private var engineBackend = "uninitialized"

    // Only the "curious" free-chat mode keeps a persistent multi-turn conversation; the
    // deterministic Q1-Q3 flow never calls Gemma, and the final card search is a short-lived,
    // single-shot conversation created fresh in produceWineCards().
    private var curiousConversation: Conversation? = null

    /**
     * Lookups for the open conversation (Grape_Profile_Internal, Grape_Profile_Kaggle_Extracted, reviews and the
     * Wineries_Directory), added to a question only when it needs them. Set once from the app.
     */
    @Volatile
    private var curiousExtras: AskExtras? = null

    fun attachCuriousExtras(extras: AskExtras?) {
        curiousExtras = extras
    }
    private val usedConversationOpeners = mutableSetOf<String>()

    private var chatMode = ChatMode.Undecided

    /** Which path handled the most recent turn: GEMMA_WAKE, KOTLIN_HANDLED or HANDBACK. Logging only. */
    @Volatile
    internal var lastRoute: String? = null
        private set

    private fun markRoute(route: String) {
        lastRoute = route
        logFlow("ROUTE $route mode=$chatMode")
    }
    private var findWineStep = FindWineStep.Type
    private var chatPreferences = WinePreferences()

    // A step the user explicitly declined ("no preference", "skip", ...) stays UNKNOWN in
    // chatPreferences by design — that's indistinguishable from "not yet asked" on the
    // WinePreferences fields alone, so advanceFindWine needs this separate record to avoid
    // re-asking the same question forever.
    private val declinedSteps = mutableSetOf<FindWineStep>()

    private fun loadPrompt(assetName: String): String =
        appContext.assets.open("prompts/$assetName").bufferedReader().use { it.readText() }

    private val curiousChatInstruction: String by lazy { loadPrompt("curious_chat_instruction.txt") }
    private val chatSearchInstruction: String by lazy { loadPrompt("chat_search_instruction.txt") }
    private val wineDetailInstruction: String by lazy { loadPrompt("wine_detail_instruction.txt") }
    private val webResultSystemInstruction: String by lazy { loadPrompt("web_result_system_instruction.txt") }
    private val wineDiscussionInstruction: String by lazy { loadPrompt("wine_discussion_instruction.txt") }

    override suspend fun replyTo(query: String): ChatMessage =
        replyToUpdates(query) {}

    override suspend fun replyToStreaming(
        query: String,
        onPartialText: (String) -> Unit,
    ): ChatMessage = replyToUpdates(query) { update -> onPartialText(update.text) }

    override suspend fun replyToUpdates(
        query: String,
        onUpdate: (ConversationStreamUpdate) -> Unit,
    ): ChatMessage = requestMutex.withLock {
        lastRoute = null
        when (chatMode) {
            ChatMode.Undecided -> handleModeChoice(query)
            ChatMode.Curious -> handleCuriousChat(query, onUpdate)
            ChatMode.FindWine -> handleFindWineTurn(query)
        }
    }

    private fun handleModeChoice(query: String): ChatMessage {
        markRoute("KOTLIN_HANDLED")
        return handleModeChoiceInner(query)
    }

    private fun handleModeChoiceInner(query: String): ChatMessage =
        when (matchModeChoice(query)) {
            ModeChoice.Curious -> {
                chatMode = ChatMode.Curious
                plainMessage(ChatFlowText.CURIOUS_TRANSITION)
            }
            ModeChoice.FindWine -> {
                chatMode = ChatMode.FindWine
                findWineStep = FindWineStep.Type
                chatPreferences = WinePreferences()
                declinedSteps.clear()
                plainMessage(ChatFlowText.questionFor(findWineStep))
            }
            null -> plainMessage(ChatFlowText.apology(ChatFlowText.MODE_CHOICE))
        }

    private suspend fun handleCuriousChat(
        query: String,
        onUpdate: (ConversationStreamUpdate) -> Unit,
    ): ChatMessage {
        if (requestsFindWineSwitch(query)) {
            markRoute("HANDBACK")
            chatMode = ChatMode.FindWine
            findWineStep = FindWineStep.Type
            chatPreferences = WinePreferences()
            declinedSteps.clear()
            return plainMessage(
                "${ChatFlowText.FIND_WINE_HANDOVER_INTRO}\n\n${ChatFlowText.questionFor(findWineStep)}",
                quickReplies = listOf(ChatFlowText.CONTINUE_CONVERSATION_LABEL),
            )
        }

        markRoute("GEMMA_WAKE")
        suspend fun messageFor(): String {
            val extra = runCatching { curiousExtras?.forQuestion(query) }.getOrNull()
            return if (extra.isNullOrBlank()) query else "<more_context>\n$extra\n</more_context>\nThe user says: $query"
        }
        suspend fun streamFrom(conversation: Conversation, message: String): String {
            val requestStartedAt = SystemClock.elapsedRealtime()
            var firstWordAt: Long? = null
            var lastWordAt = requestStartedAt
            var lastText = ""
            val response = buildString {
                conversation.sendMessageAsync(message).collect { reply ->
                    reply.contents.contents.filterIsInstance<Content.Text>().forEach { content ->
                        if (content.text.isNotEmpty()) {
                            val now = SystemClock.elapsedRealtime()
                            if (firstWordAt == null) firstWordAt = now
                            lastWordAt = now
                        }
                        append(content.text)
                        if (toString() != lastText) {
                            lastText = toString()
                            onUpdate(ConversationStreamUpdate(text = lastText, isGemmaConversationOutput = true))
                        }
                    }
                }
            }.trim()
            firstWordAt?.let { firstWord ->
                DebugLatencyLog.record("[Gemma] curious chat: time to first word", firstWord - requestStartedAt)
                DebugLatencyLog.record("[Gemma] curious chat: first word to last word", lastWordAt - firstWord)
            }
            logFlow(
                "Gemma curious chat totalMs=${SystemClock.elapsedRealtime() - requestStartedAt}, " +
                    "chars=${response.length}",
            )
            return response
        }

        val activeConversation = ensureCuriousConversation()
        val response = try {
            streamFrom(activeConversation, messageFor())
        } catch (error: Throwable) {
            if (!error.isContextCapacityError()) throw error
            logFlow("Gemma context exhausted; rebuilding curious conversation and retrying turn once")
            withContext(Dispatchers.Default) {
                curiousConversation?.close()
                curiousConversation = null
            }
            // The new conversation has lost any lookups sent earlier, so they are sent again where needed.
            curiousExtras?.reset()
            streamFrom(ensureCuriousConversation(), messageFor())
        }
        val visible = response.withoutRepeatedConversationOpener(recordUsage = true)
        return plainMessage(visible.ifBlank { "Could you say a bit more about that?" })
    }

    private suspend fun handleFindWineTurn(query: String): ChatMessage {
        if (requestsContinueConversation(query)) {
            markRoute("KOTLIN_HANDLED")
            chatMode = ChatMode.Curious
            findWineStep = FindWineStep.Type
            chatPreferences = WinePreferences()
            declinedSteps.clear()
            return plainMessage(ChatFlowText.CURIOUS_TRANSITION)
        }
        markRoute("KOTLIN_HANDLED")
        val noPreference = isNoPreference(query)
        return when (findWineStep) {
            FindWineStep.Type -> {
                val matched = matchWineType(query)
                when {
                    noPreference -> {
                        chatPreferences = chatPreferences.copy(type = WinePreferences.UNKNOWN)
                        declinedSteps += FindWineStep.Type
                    }
                    matched != null -> chatPreferences = chatPreferences.copy(type = matched)
                    else -> return unmatchedFindWineAnswer(query, ChatFlowText.Q1_TYPE)
                }
                if (!noPreference) applyOpportunisticMatches(query)
                advanceFindWine()
            }
            FindWineStep.Country -> {
                val matched = matchLocation(query)
                when {
                    noPreference -> {
                        chatPreferences = chatPreferences.copy(
                            country = WinePreferences.UNKNOWN,
                            province = WinePreferences.UNKNOWN,
                        )
                        declinedSteps += FindWineStep.Country
                    }
                    matched != null -> chatPreferences = chatPreferences.copy(
                        country = matched.country,
                        province = matched.province,
                    )
                    else -> return unmatchedFindWineAnswer(query, ChatFlowText.Q2_COUNTRY)
                }
                if (!noPreference) applyOpportunisticMatches(query)
                advanceFindWine()
            }
            FindWineStep.Taste -> {
                val matched = matchTaste(query)
                when {
                    noPreference -> declinedSteps += FindWineStep.Taste
                    !matched.isEmpty -> chatPreferences = chatPreferences.copy(
                        body = matched.body ?: chatPreferences.body,
                        tannin = matched.tannin ?: chatPreferences.tannin,
                        acidity = matched.acidity ?: chatPreferences.acidity,
                        sweetness = matched.sweetness ?: chatPreferences.sweetness,
                    )
                    else -> return unmatchedFindWineAnswer(query, ChatFlowText.Q3_TASTE)
                }
                advanceFindWine()
            }
        }
    }

    /**
     * A reply can answer more than one Q1-Q3 question at once (e.g. "French Red" to Q1 also
     * answers Q2). After the step the user was actually asked resolves, every other still-open
     * step's matcher also runs against the same reply — any hit is folded in as a bonus, a miss
     * is not an error (the corresponding question is simply asked normally later).
     */
    private fun applyOpportunisticMatches(query: String) {
        if (FindWineStep.Country !in declinedSteps && !chatPreferences.isStepResolved(FindWineStep.Country)) {
            matchLocation(query)?.let {
                chatPreferences = chatPreferences.copy(country = it.country, province = it.province)
            }
        }
        if (FindWineStep.Taste !in declinedSteps && !chatPreferences.isStepResolved(FindWineStep.Taste)) {
            val taste = matchTaste(query)
            if (!taste.isEmpty) {
                chatPreferences = chatPreferences.copy(
                    body = taste.body ?: chatPreferences.body,
                    tannin = taste.tannin ?: chatPreferences.tannin,
                    acidity = taste.acidity ?: chatPreferences.acidity,
                    sweetness = taste.sweetness ?: chatPreferences.sweetness,
                )
            }
        }
    }

    /** Advances to the first still-open Q1-Q3 step, skipping any already resolved by a
     * compound answer or explicitly declined with "no preference" — or finalizes immediately
     * once none remain. */
    private fun advanceFindWine(): ChatMessage {
        val next = FindWineStep.entries.firstOrNull {
            it !in declinedSteps && !chatPreferences.isStepResolved(it)
        } ?: return finalizePreferences()
        findWineStep = next
        return plainMessage(ChatFlowText.questionFor(next))
    }

    /**
     * The Q1-Q3 flow is complete: hand the fully-resolved [WinePreferences] back to the caller
     * instead of calling Gemma here. This lets a wrapping responder (e.g. one that also queries
     * a local database) start its own lookup from these recorded answers at the same moment it
     * kicks off [synthesizeCards], rather than waiting for that model call to finish first.
     */
    private fun finalizePreferences(): ChatMessage {
        val preferences = chatPreferences
        // A "find a wine" cycle is one-shot: the next message starts fresh from the mode choice.
        chatMode = ChatMode.Undecided
        findWineStep = FindWineStep.Type
        chatPreferences = WinePreferences()
        declinedSteps.clear()
        return ChatMessage(
            id = System.nanoTime(),
            author = MessageAuthor.Assistant,
            text = "",
            coverageComplete = true,
            resolvedPreferences = preferences,
        )
    }

    /**
     * A deterministic Q1-Q3 answer that didn't match: either genuine noise (apologize and
     * repeat the question, no model call) or a real tangent/question, in which case Gemma
     * answers it briefly and then hands control straight back to Kotlin by re-appending the
     * still-pending fixed question underneath.
     */
    private suspend fun unmatchedFindWineAnswer(query: String, pendingQuestion: String): ChatMessage {
        if (!isLikelyDigression(query)) return plainMessage(ChatFlowText.apology(pendingQuestion))
        markRoute("GEMMA_WAKE")

        val requestStartedAt = SystemClock.elapsedRealtime()
        var firstWordAt: Long? = null
        var lastWordAt = requestStartedAt
        val digressionConversation = ensureEngine().createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(curiousChatInstruction),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.90, temperature = 0.5),
                maxOutputToken = 256,
            ),
        )
        val answer = try {
            buildString {
                digressionConversation.sendMessageAsync(query).collect { message ->
                    message.contents.contents.filterIsInstance<Content.Text>().forEach { content ->
                        if (content.text.isNotEmpty()) {
                            val now = SystemClock.elapsedRealtime()
                            if (firstWordAt == null) firstWordAt = now
                            lastWordAt = now
                        }
                        append(content.text)
                    }
                }
            }.trim()
        } finally {
            digressionConversation.close()
        }
        firstWordAt?.let { firstWord ->
            DebugLatencyLog.record("[Gemma] digression: time to first word", firstWord - requestStartedAt)
            DebugLatencyLog.record("[Gemma] digression: first word to last word", lastWordAt - firstWord)
        }
        return plainMessage(
            listOf(answer, pendingQuestion).filter(String::isNotBlank).joinToString("\n\n"),
        )
    }

    override suspend fun synthesizeCards(
        preferences: WinePreferences,
        onUpdate: (ConversationStreamUpdate) -> Unit,
    ): ChatMessage {
        val timing = GemmaTimingTrace(appContext.filesDir)
        try {
            return timing.measure("queue_wait_and_work") {
                val queuedAt = SystemClock.elapsedRealtime()
                requestMutex.withLock {
                    timing.duration("queue_wait", SystemClock.elapsedRealtime() - queuedAt)
                    timing.measure("card_search_total") { produceWineCards(preferences, timing, onUpdate) }
                }
            }
        } catch (error: Throwable) {
            timing.detail("failure", error.javaClass.simpleName)
            throw error
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { timing.save() }
        }
    }

    private suspend fun produceWineCards(
        preferences: WinePreferences,
        timing: GemmaTimingTrace,
        onUpdate: (ConversationStreamUpdate) -> Unit,
    ): ChatMessage {
        val requestStartedAt = SystemClock.elapsedRealtime()
        timing.detail("engine_reused", (engine != null).toString())
        val searchEngine = timing.measure("engine_ready") { ensureEngine() }
        timing.detail("backend", engineBackend)
        val instruction = timing.measure("load_prompt") { chatSearchInstruction }
        val prompt = timing.measure("prepare_preferences") {
            preferences.toCompactJson()
        }
        GemmaEvalTrace.active?.let { it.systemInstruction = instruction; it.userMessage = prompt }
        timing.detail("input_characters", (instruction.length + prompt.length).toString())
        timing.detail("max_output_tokens", INITIAL_CARD_MAX_OUTPUT_TOKENS.toString())
        val searchConversation = timing.measure("create_conversation") {
            searchEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(instruction),
                    samplerConfig = SamplerConfig(topK = CARD_TOP_K, topP = CARD_TOP_P, temperature = CARD_TEMPERATURE),
                    maxOutputToken = INITIAL_CARD_MAX_OUTPUT_TOKENS,
                ),
            )
        }
        val completedCards = mutableListOf<WineSuggestion>()
        val cardStream = CompleteJsonObjects()
        var completeObjectCount = 0
        var rejectedCardCount = 0

        fun rejectCard(candidateKey: String, reasons: List<String>, card: WineSuggestion? = null) {
            rejectedCardCount++
            GemmaEvalTrace.active?.repair(
                "card_dropped", candidateKey,
                reasons.distinct().joinToString(",") + (card?.let {
                    " | name=${it.name}; country=${it.country}; province=${it.province}; variety=${it.variety}"
                } ?: ""),
            )
            timing.detail(
                "${candidateKey}_rejected",
                reasons.distinct().joinToString(","),
            )
        }

        fun acceptParsedCard(card: WineSuggestion, candidateKey: String): Boolean {
            if (completedCards.size >= RECOMMENDATION_COUNT) {
                rejectCard(candidateKey, listOf("extra_complete_object"), card)
                return false
            }
            // Publish the compact card immediately. The detail screen asks Gemma for the
            // remaining profile fields only if the user opens this recommendation.
            // Keep the original request with this wine style so the detail call can answer
            // only the preferences the user actually supplied.
            val profile = card.copy(
                requestContext = preferences.toCompactJson(),
                profileComplete = false,
            )
            val reasons = mutableListOf<String>()
            when {
                profile.name.isBlank() -> reasons += "blank_name"
                profile.name.equals("Unknown Wine", true) -> reasons += "unknown_name"
                profile.name.looksLikeFieldNameEcho() -> reasons += "field_name_echo"
                profile.name.looksLikeHallucinatedMarkup() -> reasons += "name_contains_markup"
            }
            reasons += missingGemmaCardFields(profile)
            if (completedCards.any {
                    it.name.equals(profile.name, true) && it.country.equals(profile.country, true) &&
                        it.province.equals(profile.province, true) && it.variety.equals(profile.variety, true)
                }
            ) reasons += "duplicate_card"
            if (reasons.isNotEmpty()) {
                rejectCard(candidateKey, reasons, card)
                return false
            }
            completedCards += profile
            timing.duration("card_${completedCards.size}_ready", SystemClock.elapsedRealtime() - requestStartedAt)
            onUpdate(ConversationStreamUpdate(text = "", suggestions = completedCards.toList()))
            return true
        }

        fun acceptCard(payload: String) {
            completeObjectCount++
            val candidateKey = "candidate_$completeObjectCount"
            val card = payload.toWineSuggestionOrNull()
            if (card == null) {
                GemmaEvalTrace.active?.let { it.parseFailures++; it.repair("json_parse_failed", candidateKey, payload.take(300)) }
                rejectCard(candidateKey, listOf("json_parse_failed"))
                return
            }
            acceptParsedCard(card, candidateKey)
        }
        var firstWordAt: Long? = null
        var lastWordAt = requestStartedAt
        val sendStartedAt = SystemClock.elapsedRealtime()
        var outputCharacters = 0
        var outputChunks = 0
        var incrementalParsingMs = 0L
        val response = try {
            timing.measure("send_to_stream_complete") {
                buildString {
                    searchConversation.sendMessageAsync(prompt).collect { message ->
                        message.contents.contents.filterIsInstance<Content.Text>().forEach { content ->
                            if (content.text.isNotEmpty()) {
                                val now = SystemClock.elapsedRealtime()
                                if (firstWordAt == null) {
                                    firstWordAt = now
                                    timing.duration("send_to_first_output", now - sendStartedAt)
                                }
                                outputChunks++
                                outputCharacters += content.text.length
                                lastWordAt = now
                            }
                            append(content.text)
                            GemmaEvalTrace.active?.let { it.raw.append(content.text); it.phase = "stream_parse" }
                            val parsingStarted = SystemClock.elapsedRealtime()
                            cardStream.append(content.text).forEach(::acceptCard)
                            incrementalParsingMs += SystemClock.elapsedRealtime() - parsingStarted
                        }
                    }
                    firstWordAt?.let {
                        timing.duration("last_output_to_stream_complete", SystemClock.elapsedRealtime() - lastWordAt)
                    }
                }.trim()
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            timing.detail("stream_failure", error.javaClass.simpleName)
            if (completedCards.isEmpty()) throw error
            "" // Keep already published cards when a later part of generation fails.
        } finally {
            timing.duration("incremental_parse_and_publish", incrementalParsingMs)
            timing.detail("stream_parser_start", cardStream.startMode)
            timing.detail("stream_parser_final_depth", cardStream.unfinishedObjectDepth.toString())
            firstWordAt?.let { timing.duration("first_to_last_output", lastWordAt - it) }
            timing.detail("output_characters", outputCharacters.toString())
            timing.detail("output_chunks", outputChunks.toString())
            timing.measure("close_conversation") { searchConversation.close() }
        }
        firstWordAt?.let { firstWord ->
            DebugLatencyLog.record("[Gemma] card search: time to first word", firstWord - requestStartedAt)
            DebugLatencyLog.record("[Gemma] card search: first word to last word", lastWordAt - firstWord)
        }
        GemmaEvalTrace.active?.phase = "final_parse"
        // Validate every parsed card before the three-card limit is applied. An incomplete
        // early card must not crowd out a later complete one in the final response.
        val parsedSuggestions = timing.measure("parse_response") { extractSuggestions(response) }
        GemmaEvalTrace.active?.let {
            it.parsedCards = parsedSuggestions.size
            it.parseOk = parsedSuggestions.isNotEmpty() && it.parseFailures == 0
            it.phase = "final_validate"
        }
        var recoveredCards = 0
        val suggestions = timing.measure("validate_cards") {
            // The streaming extractor can be confused by malformed text before an otherwise
            // valid card array. Recover any cards the final-response parser can still read,
            // while retaining cards already published progressively.
            parsedSuggestions.forEachIndexed { index, card ->
                val alreadyPublished = completedCards.any {
                    it.name.equals(card.name, true) && it.country.equals(card.country, true) &&
                        it.province.equals(card.province, true) && it.variety.equals(card.variety, true)
                }
                if (!alreadyPublished && acceptParsedCard(card, "final_candidate_${index + 1}")) {
                    recoveredCards++
                }
            }
            completedCards.toList()
        }
        timing.detail("parsed_cards", parsedSuggestions.size.toString())
        timing.detail("usable_cards", suggestions.size.toString())
        timing.detail("recovered_final_cards", recoveredCards.toString())
        timing.detail("complete_objects", completeObjectCount.toString())
        timing.detail("rejected_cards", rejectedCardCount.toString())
        timing.detail(
            "missing_complete_objects",
            (RECOMMENDATION_COUNT - completeObjectCount).coerceAtLeast(0).toString(),
        )
        val searchElapsedMs = SystemClock.elapsedRealtime() - requestStartedAt
        DebugLatencyLog.record("[Gemma] card search: total", searchElapsedMs)
        logFlow(
            "Gemma chat search parsed=${parsedSuggestions.size}, usable=${suggestions.size}, " +
                "totalMs=$searchElapsedMs",
        )

        return ChatMessage(
            id = System.nanoTime(),
            author = MessageAuthor.Assistant,
            text = if (suggestions.isNotEmpty()) {
                "I found these options for you."
            } else {
                "I couldn’t find a confident match for that — want to try again?"
            },
            suggestion = suggestions.firstOrNull(),
            suggestions = suggestions,
            stageOneOutput = suggestions.isNotEmpty(),
            coverageComplete = true,
            discardedStageOneCards = (parsedSuggestions.size - suggestions.size).coerceAtLeast(0),
        )
    }

    private fun plainMessage(text: String, quickReplies: List<String> = emptyList()): ChatMessage = ChatMessage(
        id = System.nanoTime(),
        author = MessageAuthor.Assistant,
        text = text,
        quickReplies = quickReplies,
    )

    suspend fun enrichWineDetails(
        card: WineSuggestion,
        onPartial: (WineSuggestion) -> Unit = {},
    ): WineSuggestion = withContext(Dispatchers.Default) {
        val timing = StageShowTimingTrace(appContext.filesDir)
        val loadStartedAt = SystemClock.elapsedRealtime()
        val pendingBefore = card.pendingDetailFieldCount()
        timing.detail("source", card.source.name)
        timing.detail("pending_fields", pendingBefore.toString())
        try {
            val queuedAt = SystemClock.elapsedRealtime()
            val enriched = requestMutex.withLock {
                timing.duration("queue_wait", SystemClock.elapsedRealtime() - queuedAt)
                if (card.profileComplete || card.source != WineSuggestionSource.GEMMA) return@withLock card
                val detailRequest = card.toStyleCardJson()
                GemmaEvalTrace.active?.let {
                    it.systemInstruction = wineDetailInstruction
                    it.userMessage = detailRequest
                }
                val conversation = ensureEngine().createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(wineDetailInstruction),
                        samplerConfig = SamplerConfig(topK = DETAIL_TOP_K, topP = DETAIL_TOP_P, temperature = DETAIL_TEMPERATURE),
                        maxOutputToken = DETAIL_MAX_OUTPUT_TOKENS,
                    ),
                )
                val startedAt = SystemClock.elapsedRealtime()
                var firstOutputAt: Long? = null
                var lastOutputAt = startedAt
                var lastPartialUpdateAt = 0L
                val streamsEducationSummary = card.requestContext
                    ?.trimStart()
                    ?.startsWith("{") == true
                val response = try {
                    buildString {
                        conversation.sendMessageAsync(detailRequest).collect { message ->
                            message.contents.contents.filterIsInstance<Content.Text>().forEach { content ->
                                if (content.text.isNotEmpty()) {
                                    val now = SystemClock.elapsedRealtime()
                                    if (firstOutputAt == null) firstOutputAt = now
                                    lastOutputAt = now
                                }
                                append(content.text)
                                GemmaEvalTrace.active?.raw?.append(content.text)
                                if (
                                    streamsEducationSummary &&
                                    isNotBlank() &&
                                    (lastPartialUpdateAt == 0L ||
                                        lastOutputAt - lastPartialUpdateAt >= DETAIL_STREAM_UPDATE_INTERVAL_MS)
                                ) {
                                    lastPartialUpdateAt = lastOutputAt
                                    val partialCard = card.copy(
                                        summary = toString(),
                                        profileComplete = false,
                                    )
                                    withContext(Dispatchers.Main.immediate) {
                                        onPartial(partialCard)
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conversation.close()
                }
                firstOutputAt?.let { first ->
                    DebugLatencyLog.record("[Gemma] wine details: time to first word", first - startedAt)
                    DebugLatencyLog.record("[Gemma] wine details: first word to last word", lastOutputAt - first)
                }
                DebugLatencyLog.record(
                    "[Gemma] wine details: total",
                    SystemClock.elapsedRealtime() - startedAt,
                )
                timing.duration("gemma_details", SystemClock.elapsedRealtime() - startedAt)
                GemmaEvalTrace.active?.phase = "detail_parse"
                mergeGeneratedDetails(card, response)
            }
            val pendingAfter = enriched.pendingDetailFieldCount()
            timing.detail("resolved_fields", (pendingBefore - pendingAfter).coerceAtLeast(0).toString())
            timing.detail("unresolved_fields", pendingAfter.toString())
            timing.detail("profile_complete", enriched.profileComplete.toString())
            timing.detail("success", enriched.profileComplete.toString())
            enriched
        } catch (error: Throwable) {
            timing.detail("success", "false")
            timing.detail("failure", error.javaClass.simpleName)
            throw error
        } finally {
            timing.duration("pending_data_load", SystemClock.elapsedRealtime() - loadStartedAt)
            withContext(Dispatchers.IO + NonCancellable) { timing.save() }
        }
    }

    private fun WineSuggestion.pendingDetailFieldCount(): Int =
        if (requestContext?.trimStart()?.startsWith("{") == true) {
            listOf(summary).count { !it.isResolvedValue() }
        } else {
            listOf(
                winery, wineType, sweetness, body, tannin, acidity,
                flavorNotes, suggestedPairing, summary,
            ).count { !it.isResolvedValue() }
        }

    private fun WineSuggestion.toStyleCardJson(): String = JSONObject().apply {
        put("variety", variety)
        put("type", when (wineType.lowercase()) {
            "rose", "rosé" -> "Rosé"
            "sweet", "dessert" -> "Dessert"
            else -> wineType.replaceFirstChar(Char::uppercase)
        })
        put("country", country)
        put("region", province)
    }.toString()

    private fun WineSuggestion.toKnownDetailJson(): String = JSONObject().apply {
        put("name", name)
        listOf(
            "winery" to winery, "wine_type" to wineType, "country" to country,
            "province" to province, "variety" to variety, "sweetness" to sweetness,
            "body" to body, "tannin" to tannin, "acidity" to acidity,
            "flavor_notes" to flavorNotes, "suggested_pairing" to suggestedPairing,
            "summary" to summary,
        ).forEach { (key, value) -> if (value.isResolvedValue()) put(key, value) }
    }.toString()

    suspend fun prepare() {
        val startedAt = SystemClock.elapsedRealtime()
        requestMutex.withLock { ensureEngine() }
        logFlow("Gemma prepared in ${SystemClock.elapsedRealtime() - startedAt}ms")
    }

    suspend fun guidedSelection(
        criteria: com.sheldondesousa.uncork.ui.guided.GuidedCriteria,
        onUpdate: (List<WineSuggestion>) -> Unit = {},
    ): List<WineSuggestion> {
        require(criteria.valid)
        val preferences = WinePreferences(
            type = criteria.wineType.ifBlank { WinePreferences.UNKNOWN },
            country = criteria.country.ifBlank { WinePreferences.UNKNOWN },
            province = criteria.province.ifBlank { WinePreferences.UNKNOWN },
            variety = criteria.variety.ifBlank { WinePreferences.UNKNOWN },
            sweetness = criteria.sweetness.ifBlank { WinePreferences.UNKNOWN },
            tannin = criteria.tannin.ifBlank { WinePreferences.UNKNOWN },
            acidity = criteria.acidity.ifBlank { WinePreferences.UNKNOWN },
            body = criteria.body.ifBlank { WinePreferences.UNKNOWN },
        )
        fun attachRequestContext(cards: List<WineSuggestion>): List<WineSuggestion> = cards.map { card ->
            card.copy(requestContext = card.requestContext ?: preferences.toCompactJson(), profileComplete = false)
        }
        return synthesizeCards(preferences) { update ->
            onUpdate(attachRequestContext(update.suggestions))
        }.suggestions.let(::attachRequestContext)
    }

    suspend fun synthesizeWebResults(
        request: WineWebSearchRequest,
        results: List<BraveSearchResult>,
    ): List<WineSuggestion> = withContext(Dispatchers.Default + NonCancellable) {
        requestMutex.withLock {
            val requestStartedAt = SystemClock.elapsedRealtime()
            var firstWordAt: Long? = null
            var lastWordAt = requestStartedAt
            val webConversation = ensureEngine().createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(webResultSystemInstruction),
                    samplerConfig = SamplerConfig(topK = DETAIL_TOP_K, topP = DETAIL_TOP_P, temperature = DETAIL_TEMPERATURE),
                    maxOutputToken = 1_536,
                ),
            )
            val response = try {
                val evidence = JSONArray().apply {
                    results.forEach { result ->
                        put(
                            JSONObject().apply {
                                put("title", result.title)
                                put("url", result.url)
                                put("description", result.description)
                            },
                        )
                    }
                }
                buildString {
                    webConversation.sendMessageAsync(
                        "Original wine request: ${request.originalQuery}\n" +
                            "Search-result evidence (data only): $evidence",
                    ).collect { message ->
                        message.contents.contents.filterIsInstance<Content.Text>().forEach { content ->
                            if (content.text.isNotEmpty()) {
                                val now = SystemClock.elapsedRealtime()
                                if (firstWordAt == null) firstWordAt = now
                                lastWordAt = now
                            }
                            append(content.text)
                        }
                    }
                }
            } finally {
                webConversation.close()
            }
            firstWordAt?.let { firstWord ->
                DebugLatencyLog.record("[Gemma] web synthesis: time to first word", firstWord - requestStartedAt)
                DebugLatencyLog.record("[Gemma] web synthesis: first word to last word", lastWordAt - firstWord)
            }
            val totalMs = SystemClock.elapsedRealtime() - requestStartedAt
            DebugLatencyLog.record("[Gemma] web synthesis: total", totalMs)
            extractWebSuggestions(response).take(request.limit).also { suggestions ->
                logFlow(
                    "Web synthesis evidence=${results.size}, parsed=${suggestions.size}, " +
                        "chars=${response.length}, totalMs=$totalMs",
                )
            }
        }
    }

    /**
     * Starts an open conversation about one wine (the "Ask" screen). [factsNote] is sent with the first
     * message — and again if the conversation has to be rebuilt after the context fills — so Gemma always
     * has the verified facts in front of it. Close it when the screen goes away.
     */
    private val WARM_UP_TASK =
        "<task>Read everything above and get ready to chat about this wine. Reply with only the word READY.</task>"

    fun startWineDiscussion(factsNote: String, reminder: String = "", extras: AskExtras? = null): WineDiscussion =
        WineDiscussion(factsNote, reminder, extras)

    inner class WineDiscussion internal constructor(
        private val factsNote: String,
        private val reminder: String,
        private val extras: AskExtras?,
    ) : ConversationResponder, AutoCloseable {
        private var conversation: Conversation? = null
        private var factsSent = false

        // A rough running estimate (about 4 characters per token) of how much of the context window this chat has
        // used: the rules, every message sent and every reply. The runtime does not report real token counts.
        private var approxTokensUsed = 0

        private fun noteUsage(label: String, sentChars: Int, replyChars: Int) {
            approxTokensUsed += (sentChars + replyChars) / 4
            logFlow("Wine discussion $label: sent $sentChars chars, reply $replyChars chars; ~$approxTokensUsed of $ENGINE_MAX_TOKENS tokens used")
        }

        /**
         * Reads the facts note in the background as soon as the Ask screen opens, so Gemma is already prepared
         * when the user types their first question. The reply is discarded. Failures are ignored: the first
         * real question then simply sends the facts itself.
         */
        suspend fun warmUp() {
            requestMutex.withLock {
                if (factsSent) return
                runCatching {
                    val message = "$factsNote\n\n$WARM_UP_TASK"
                    val reply = send(message) {}
                    factsSent = true
                    noteUsage("warm-up", message.length, reply.length)
                }
            }
        }

        override suspend fun replyTo(query: String): ChatMessage = replyToUpdates(query) {}

        override suspend fun replyToUpdates(
            query: String,
            onUpdate: (ConversationStreamUpdate) -> Unit,
        ): ChatMessage = requestMutex.withLock {
            markRoute("WINE_DISCUSSION")
            suspend fun rebuild() {
                withContext(Dispatchers.Default) {
                    conversation?.close()
                    conversation = null
                }
                factsSent = false
                extras?.reset()
            }
            var response = try {
                answer(query, onUpdate)
            } catch (error: Throwable) {
                if (!error.isContextCapacityError()) throw error
                logFlow("Gemma context exhausted; rebuilding wine discussion and retrying turn once")
                rebuild()
                answer(query, onUpdate)
            }
            if (response.isBlank()) {
                // An empty reply means the model ran out of room (or lost the thread), not that it has nothing to
                // say. Start a fresh conversation with the facts and try once more.
                logFlow("Wine discussion returned an empty reply; rebuilding and retrying once")
                rebuild()
                response = answer(query, onUpdate)
            }
            plainMessage(
                response.ifBlank { "Sorry, I lost my train of thought for a moment. Could you ask that again?" },
            )
        }

        private suspend fun answer(query: String, onUpdate: (ConversationStreamUpdate) -> Unit): String {
            // Until the facts have been read, they go right before the question. After that, a short reminder
            // does, since the first message is by then far behind the model's close-reading window. Extra context
            // (the broader reviews, notes for another grape) sits right in front of the question that needs it.
            val extra = runCatching { extras?.forQuestion(query) }.getOrNull()
                ?.let { "<more_context>\n$it\n</more_context>\n" }.orEmpty()
            val message = when {
                !factsSent -> "$factsNote\n\n${extra}The user says: $query"
                reminder.isNotBlank() -> "$reminder\n${extra}The user says: $query"
                else -> "$extra$query"
            }
            return send(message, onUpdate).also { reply ->
                factsSent = true
                noteUsage("turn", message.length, reply.length)
            }
        }

        private suspend fun send(message: String, onUpdate: (ConversationStreamUpdate) -> Unit): String {
            val active = ensureConversation()
            var lastText = ""
            return buildString {
                active.sendMessageAsync(message).collect { reply ->
                    reply.contents.contents.filterIsInstance<Content.Text>().forEach { content ->
                        append(content.text)
                        if (toString() != lastText) {
                            lastText = toString()
                            onUpdate(ConversationStreamUpdate(text = lastText, isGemmaConversationOutput = true))
                        }
                    }
                }
            }.trim()
        }

        private suspend fun ensureConversation(): Conversation {
            conversation?.let { return it }
            check(modelFile.isFile) { "The on-device model file is missing." }
            return withContext(Dispatchers.Default) {
                conversation?.let { return@withContext it }
                approxTokensUsed = wineDiscussionInstruction.length / 4
                ensureEngine().createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(wineDiscussionInstruction),
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.90, temperature = 0.5),
                        maxOutputToken = 512,
                    ),
                ).also { conversation = it }
            }
        }

        override fun close() {
            conversation?.close()
            conversation = null
        }
    }

    private suspend fun ensureCuriousConversation(): Conversation {
        curiousConversation?.let { return it }
        check(modelFile.isFile) { "The on-device model file is missing." }

        return withContext(Dispatchers.Default) {
            curiousConversation?.let { return@withContext it }
            val initializedConversation = ensureEngine().createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(curiousChatInstruction),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.90, temperature = 0.5),
                    maxOutputToken = 512,
                ),
            )
            curiousConversation = initializedConversation
            initializedConversation
        }
    }

    private suspend fun ensureEngine(): Engine {
        engine?.let { return it }
        check(modelFile.isFile) { "The on-device model file is missing." }
        val startedAt = SystemClock.elapsedRealtime()
        return withContext(Dispatchers.Default) {
            engine?.let { return@withContext it }
            runCatching { createEngine(Backend.GPU()).also { engineBackend = "GPU" } }
                .getOrElse {
                    logFlow("Gemma GPU initialization failed (${it.javaClass.simpleName}); trying CPU")
                    createEngine(Backend.CPU()).also { engineBackend = "CPU" }
                }
                .also { engine = it }
                .also { DebugLatencyLog.record("[Gemma] engine init (cold start)", SystemClock.elapsedRealtime() - startedAt) }
        }
    }

    private fun createEngine(backend: Backend): Engine {
        val candidate = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                // The default context window was too small for the Ask screen's facts note (system rules, wine
                // facts, grape notes and review sample are several thousand tokens): replies were cut off and then
                // came back empty. Ask for an explicit, larger window.
                maxNumTokens = ENGINE_MAX_TOKENS,
                cacheDir = cacheDirectory.absolutePath,
            ),
        )
        return try {
            candidate.initialize()
            candidate
        } catch (error: Throwable) {
            candidate.close()
            throw error
        }
    }

    override fun close() {
        curiousConversation?.close()
        curiousConversation = null
        curiousExtras?.reset()
        engine?.close()
        engine = null
        usedConversationOpeners.clear()
        chatMode = ChatMode.Undecided
        findWineStep = FindWineStep.Type
        chatPreferences = WinePreferences()
        declinedSteps.clear()
    }

    /**
     * Same as [close] but keeps the (expensive-to-cold-start) [engine] alive — only the
     * per-conversation state resets. Lets a caller run many independent conversations back to
     * back (e.g. an eval harness) through the exact same inference path and config a fresh
     * [GemmaConversationResponder] would use, without repaying engine initialization each time.
     */
    internal suspend fun resetConversationState() = requestMutex.withLock {
        curiousConversation?.close()
        curiousConversation = null
        curiousExtras?.reset()
        usedConversationOpeners.clear()
        chatMode = ChatMode.Undecided
        findWineStep = FindWineStep.Type
        chatPreferences = WinePreferences()
        declinedSteps.clear()
    }

    private fun String.withoutRepeatedConversationOpener(recordUsage: Boolean): String {
        val opener = CONVERSATION_OPENERS.firstOrNull { it.pattern.containsMatchIn(this) }
        if (opener != null) {
            if (opener.key in usedConversationOpeners) {
                return opener.pattern.replaceFirst(this, "").trimStart()
            }
            if (recordUsage) usedConversationOpeners += opener.key
            return this
        }

        val partialOpening = trimStart().normalizeOpener()
        if (
            !recordUsage &&
            partialOpening.isNotEmpty() &&
            usedConversationOpeners.any { used -> used.startsWith(partialOpening) }
        ) {
            return ""
        }
        return this
    }

    private fun String.normalizeOpener(): String =
        lowercase().replace(Regex("[^a-z ]"), "").replace(Regex("\\s+"), " ").trim()

    companion object {
        /** Context window requested from the on-device engine, in tokens. */
        internal const val ENGINE_MAX_TOKENS = 8192

        /** A known variety, type and country are required; region may be unknown. */
        internal fun missingGemmaCardFields(card: WineSuggestion): List<String> = buildList {
            if (!card.variety.isResolvedValue()) add("missing_variety")
            if (!card.wineType.isResolvedValue()) add("missing_wine_type")
            if (!card.country.isResolvedValue()) add("missing_country")
        }

        internal fun Throwable.isContextCapacityError(): Boolean =
            generateSequence(this) { it.cause }.any { cause ->
                cause.message?.contains(
                    "Prefill input length exceeds available state entries",
                    ignoreCase = true,
                ) == true
            }

        private val WINE_CARDS_MARKER = Regex(
            pattern = "\\[WINE_CARDS](.+?)\\[/WINE_CARDS]",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val WINE_PROFILE_MARKER = Regex(
            pattern = "\\[WINE_PROFILE](.+?)\\[/WINE_PROFILE]",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val WINE_DETAILS_MARKER = Regex(
            pattern = "\\[WINE_DETAILS](.+?)\\[/WINE_DETAILS]",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val WEB_RESULTS_MARKER = Regex(
            pattern = "\\[WEB_RESULTS](.+?)\\[/WEB_RESULTS]",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val LEGACY_WINE_MARKER = Regex(
            pattern = "\\[WINE]\\s*(.+?)\\s*\\|\\s*(.+?)\\s*\\[/WINE]",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val FENCED_JSON_BLOCK = Regex(
            pattern = "```(?:\\.?json)?\\s*([\\[{].+?[}\\]])\\s*```",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val JSON_ARRAY = Regex(
            pattern = "\\[\\s*\\{.+?\\}\\s*(?:,\\s*\\{.+?\\}\\s*)*\\]",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val JSON_OBJECT = Regex(
            pattern = "[{].+?[}]",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val EDUCATION_HEADINGS = listOf(
            "Overview",
            "Taste",
            "Where it's grown",
            "Production facts",
            "Flavours",
        )
        private val EDUCATION_SECTION = Regex(
            pattern = "^\\s*\\*\\*(Overview|Taste|Where it's grown|Production facts|Flavours|Best pairings):\\*\\*\\s*(.*?)(?=^\\s*\\*\\*(?:Overview|Taste|Where it's grown|Production facts|Flavours|Best pairings):\\*\\*|\\z)",
            options = setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
        )

        internal fun extractSuggestions(response: String): List<WineSuggestion> {
            val cardArrays = WINE_CARDS_MARKER.findAll(response)
                .flatMap { match -> match.groupValues[1].toWineSuggestions() }
                .toList()
            if (cardArrays.isNotEmpty()) return cardArrays.distinctSuggestions()

            val rawCardArrays = JSON_ARRAY.findAll(response)
                .flatMap { match -> match.value.toWineSuggestions() }
                .toList()
            if (rawCardArrays.isNotEmpty()) return rawCardArrays.distinctSuggestions()

            val markedProfiles = WINE_PROFILE_MARKER.findAll(response)
                .map { it.groupValues[1] }
                .toList()
            val fencedProfiles = FENCED_JSON_BLOCK.findAll(response)
                .map { it.groupValues[1] }
                .mapNotNull { payload -> payload.toWineSuggestionOrNull() }
                .toList()
            val rawProfiles = JSON_OBJECT.findAll(response)
                .map(MatchResult::value)
                .toList()
            val parsed = markedProfiles.mapNotNull { payload -> payload.toWineSuggestionOrNull() }
                .ifEmpty { fencedProfiles }
                .ifEmpty { rawProfiles.mapNotNull { payload -> payload.toWineSuggestionOrNull() } }
                .distinctSuggestions()
            if (parsed.isNotEmpty()) return parsed

            val match = LEGACY_WINE_MARKER.find(response) ?: return emptyList()
            val name = match.groupValues[1].trim()
            val province = match.groupValues[2].trim()
            if (name.isBlank() || province.isBlank()) return emptyList()
            return listOf(WineSuggestion(name = name, province = province))
        }

        internal fun extractWebSuggestions(response: String): List<WineSuggestion> {
            val marked = WEB_RESULTS_MARKER.findAll(response)
                .flatMap { match -> match.groupValues[1].toWebSuggestions() }
                .toList()
            if (marked.isNotEmpty()) return marked.distinctSuggestions()
            return JSON_ARRAY.findAll(response)
                .flatMap { match -> match.value.toWebSuggestions() }
                .toList()
                .distinctSuggestions()
        }

        internal fun mergeGeneratedDetails(
            original: WineSuggestion,
            response: String,
        ): WineSuggestion {
            if (original.requestContext?.trimStart()?.startsWith("{") == true) {
                val education = normalizeWineEducation(response) ?: run {
                    GemmaEvalTrace.active?.let {
                        it.parseOk = false
                        it.repair("education_format_invalid", "response", "required headings missing")
                    }
                    return original
                }
                GemmaEvalTrace.active?.let { it.parseOk = true; it.parsedCards = 1 }
                return original.copy(summary = education, profileComplete = true)
            }
            val markerPayload = WINE_DETAILS_MARKER.find(response)?.groupValues?.get(1)
            val payload = markerPayload
                ?: JSON_OBJECT.find(response)?.value
                ?: run {
                    GemmaEvalTrace.active?.let { it.parseOk = false; it.repair("no_json_found", "response", "card returned unchanged") }
                    return original
                }
            if (markerPayload == null) {
                GemmaEvalTrace.active?.repair("markers_missing", "response", "used first raw JSON object instead of [WINE_DETAILS] block")
            }
            val details = payload.toWineSuggestionOrNull() ?: run {
                GemmaEvalTrace.active?.let { it.parseOk = false; it.repair("json_parse_failed", "response", payload.take(300)) }
                return original
            }
            GemmaEvalTrace.active?.let { it.parseOk = true; it.parsedCards = 1 }
            val requested = original.requestContext
                ?.takeIf { it.trimStart().startsWith("{") }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            fun keepKnown(field: String, current: String, generated: String): String {
                if (current.isResolvedValue()) {
                    if (generated.isResolvedValue() && generated != current) {
                        GemmaEvalTrace.active?.repair("generated_ignored_known_value", field, "kept $current, discarded $generated")
                    }
                    return current
                }
                return generated
            }
            fun requestedValue(requestKey: String, field: String, current: String, generated: String): String {
                if (requested != null && requested.optString(requestKey).let {
                        it.isBlank() || it.equals("Unknown", ignoreCase = true)
                    }
                ) return current
                return keepKnown(field, current, generated)
            }
            return original.copy(
                winery = if (requested != null) {
                    original.winery
                } else {
                    keepKnown("winery", original.winery, details.winery)
                },
                wineType = keepKnown("wine_type", original.wineType, details.wineType),
                sweetness = requestedValue("sweetness", "sweetness", original.sweetness, details.sweetness),
                body = requestedValue("body", "body", original.body, details.body),
                tannin = requestedValue("tannin", "tannin", original.tannin, details.tannin),
                acidity = requestedValue("acidity", "acidity", original.acidity, details.acidity),
                flavorNotes = requestedValue("flavor", "flavor_notes", original.flavorNotes, details.flavorNotes),
                suggestedPairing = requestedValue("occasion", "suggested_pairing", original.suggestedPairing, details.suggestedPairing),
                summary = keepKnown("summary", original.summary, details.summary),
                profileComplete = true,
            )
        }

        internal fun normalizeWineEducation(response: String): String? {
            val sections = EDUCATION_SECTION.findAll(response.trim()).map { match ->
                match.groupValues[1] to match.groupValues[2].trim()
            }.filterNot { it.first == "Best pairings" }.toList()
            if (sections.map { it.first } != EDUCATION_HEADINGS) return null
            if (sections.any { it.second.isBlank() }) return null
            return sections.joinToString("\n\n") { (heading, content) -> "**$heading:** $content" }
        }

        private fun String.toWineSuggestionOrNull(): WineSuggestion? = runCatching {
            val json = JSONObject(trim())
            val rawCountry = json.knownString("country")
            val rawProvince = json.knownString("province").let { location ->
                // If both synonyms appear, a real region beats an "Unknown" province.
                if (location.isResolvedValue()) location else json.knownString("region")
            }
            val rawWineType = json.knownString("wine_type")
            val province = rawProvince
            val country = canonicalCountry(rawCountry) ?: rawCountry
            val wineType = canonicalWineType(rawWineType) ?: "Unknown"
            GemmaEvalTrace.active?.let { trace ->
                if (country != rawCountry) trace.repair("label_rewritten", "country", "$rawCountry -> $country")
                if (wineType != rawWineType) trace.repair("label_rewritten", "wine_type", "$rawWineType -> $wineType")
                val rawSummary = json.knownString("summary")
                if (rawSummary.length > MAX_SUMMARY_CHARACTERS) {
                    trace.repair("summary_truncated", "summary", "${rawSummary.length} chars cut to $MAX_SUMMARY_CHARACTERS")
                }
            }
            WineSuggestion(
                // Card 1 now returns a wine style rather than a bottle. Use its variety as
                // the stable title/identity expected by the existing navigation and favorites.
                name = json.knownString(
                    "name",
                    fallback = json.knownString("variety", fallback = "Unknown Wine"),
                ),
                province = province,
                country = country,
                wineType = wineType,
                winery = json.knownString("winery"),
                variety = json.knownString("variety"),
                sweetness = json.level("sweetness"),
                body = json.level("body"),
                tannin = json.level("tannin"),
                acidity = json.level("acidity"),
                flavorNotes = json.stringList("flavor_notes"),
                preferenceFlavor = json.knownString("flavor"),
                occasion = json.knownString("occasion"),
                suggestedPairing = json.knownString("suggested_pairing"),
                summary = json.knownString("summary").take(MAX_SUMMARY_CHARACTERS),
            )
        }.onFailure { error ->
            GemmaEvalTrace.active?.repair("json_parse_failed", "payload", "${error.javaClass.simpleName}: ${take(300)}")
        }.getOrNull()

        private fun String.toWineSuggestions(): List<WineSuggestion> = runCatching {
            val array = JSONArray(trim())
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toString()?.toWineSuggestionOrNull()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

        private fun String.toWebSuggestions(): List<WineSuggestion> {
            val array = runCatching { JSONArray(trim()) }.getOrNull() ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val suggestion = runCatching {
                        WineSuggestion(
                            name = json.knownString("name", fallback = "Unknown Wine"),
                            winery = json.knownString("winery"),
                            country = json.knownString("country"),
                            province = json.knownString("province"),
                            variety = json.knownString("variety"),
                            body = json.level("body"),
                            tannin = json.level("tannin"),
                            acidity = json.level("acidity"),
                            flavorNotes = json.stringList("flavor_notes"),
                            suggestedPairing = json.knownString("suggested_pairing"),
                            summary = "Unknown",
                            rating = null,
                            reviewSummary = "Unknown",
                            webSummary = json.knownString("web_summary")
                                .take(MAX_WEB_SUMMARY_CHARACTERS),
                            source = WineSuggestionSource.WEB_SEARCH,
                        )
                    }.getOrNull()
                    if (suggestion != null) add(suggestion)
                }
            }
        }

        private fun List<WineSuggestion>.distinctSuggestions(): List<WineSuggestion> =
            distinctBy { suggestion ->
                listOf(
                    suggestion.name,
                    suggestion.winery,
                    suggestion.country,
                    suggestion.province,
                    suggestion.variety,
                ).joinToString("|") { it.trim().lowercase() }
            }.also { distinct ->
                if (distinct.size != size) {
                    GemmaEvalTrace.active?.repair("cards_deduplicated", "cards", "$size -> ${distinct.size}")
                }
            }

        private fun JSONObject.knownString(key: String, fallback: String = "Unknown"): String {
            val resolvedKey = resolvedCardKey(key)
            val raw = optString(resolvedKey ?: key)
            val cleaned = raw.trim().trimEnd(':', ';', ',').trim()
            val result = cleaned.takeIf {
                it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true)
            } ?: fallback
            GemmaEvalTrace.active?.let { trace ->
                when {
                    resolvedKey == null -> trace.repair("missing_field_set_to_fallback", key, "absent -> $fallback")
                    resolvedKey != key -> trace.repair("key_renamed", key, "$resolvedKey -> $key")
                }
                if (resolvedKey != null && result == fallback && cleaned != fallback) {
                    trace.repair("placeholder_set_to_fallback", key, "\"$raw\" -> $fallback")
                } else if (resolvedKey != null && raw != result) {
                    trace.repair("value_trimmed", key, "\"$raw\" -> \"$result\"")
                }
            }
            return result
        }

        /** Prefer the canonical key when present, then accept a recognised Gemma key alias. */
        private fun JSONObject.resolvedCardKey(canonicalKey: String): String? {
            if (has(canonicalKey)) return canonicalKey
            val keys = keys()
            while (keys.hasNext()) {
                val candidate = keys.next()
                if (GemmaCardFields.canonicalName(candidate) == canonicalKey) return candidate
            }
            return null
        }

        // A weak on-device model occasionally echoes the schema's own field name back as the
        // value (e.g. "name." or "Name") instead of a real wine — never a genuine wine name.
        private fun String.looksLikeFieldNameEcho(): Boolean =
            trim().trimEnd('.').equals("name", ignoreCase = true) ||
                trim().trimEnd('.').equals("wine name", ignoreCase = true)

        // Also seen: a hallucinated citation/footnote, e.g. "Domaine X [75](https://en.wikipedia
        // .org/...)" — a markdown link or bare URL is never part of a genuine wine name.
        private val MARKDOWN_OR_URL_PATTERN = Regex("\\[[^\\]]*]\\([^)]*\\)|https?://|www\\.")

        private fun String.looksLikeHallucinatedMarkup(): Boolean =
            MARKDOWN_OR_URL_PATTERN.containsMatchIn(this)

        private fun JSONObject.stringList(key: String): String {
            val resolvedKey = resolvedCardKey(key) ?: key
            val array = optJSONArray(resolvedKey) ?: return knownString(key)
            val joined = buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }.takeIf(List<String>::isNotEmpty)?.joinToString(", ") ?: "Unknown"
            GemmaEvalTrace.active?.repair("array_joined_to_string", key, "-> $joined")
            return joined
        }

        private fun JSONObject.level(key: String): String {
            val value = knownString(key)
            // Body, tannin and acidity are shown exactly as Gemma returned them.
            if (key == "body" || key == "tannin" || key == "acidity") return value
            val canonical = when (key) {
                "sweetness" -> canonicalSweetness(value)
                else -> null
            }
            GemmaEvalTrace.active?.let { trace ->
                if (value.isResolvedValue()) {
                    if (canonical == null) trace.repair("label_unmapped_set_unknown", key, "$value -> Unknown")
                    else if (canonical != value) trace.repair("label_rewritten", key, "$value -> $canonical")
                }
            }
            return canonical ?: "Unknown"
        }

        private const val RECOMMENDATION_COUNT = 3
        // Sampling used by the card-list (Call 1) and detail-screen (Call 2) requests. Internal so
        // the debug eval runner can record exactly what the app used.
        internal const val CARD_TOP_K = 30
        internal const val CARD_TOP_P = 0.85
        internal const val CARD_TEMPERATURE = 0.4
        internal const val DETAIL_TOP_K = 30
        internal const val DETAIL_TOP_P = 0.85
        internal const val DETAIL_TEMPERATURE = 0.35
        private const val INITIAL_CARD_MAX_OUTPUT_TOKENS = 384
        private const val DETAIL_MAX_OUTPUT_TOKENS = 512
        private const val DETAIL_STREAM_UPDATE_INTERVAL_MS = 80L
        // The prompt asks Gemma for a summary "under 200 characters", but models don't hit an
        // exact count reliably — allow a 10% buffer (220) rather than hard-truncating a
        // slightly-over response mid-word.
        private const val MAX_SUMMARY_CHARACTERS = 220
        private const val MAX_WEB_SUMMARY_CHARACTERS = 320
        private fun String.isResolvedValue(): Boolean =
            isNotBlank() && !equals("Unknown", ignoreCase = true)
        private data class ConversationOpener(val key: String, val pattern: Regex)
        private val CONVERSATION_OPENERS = listOf(
            ConversationOpener(
                key = "thats a good starting point",
                pattern = Regex(
                    "^\\s*that['’]s a good starting point[.!,:;—-]*\\s*",
                    RegexOption.IGNORE_CASE,
                ),
            ),
            ConversationOpener(
                key = "great choice",
                pattern = Regex("^\\s*great choice[.!,:;—-]*\\s*", RegexOption.IGNORE_CASE),
            ),
            ConversationOpener(
                key = "sounds good",
                pattern = Regex("^\\s*sounds good[.!,:;—-]*\\s*", RegexOption.IGNORE_CASE),
            ),
        )
    }
}
