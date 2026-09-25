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
import com.sheldondesousa.uncork.ui.guided.GuidedOptions
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
    private val usedConversationOpeners = mutableSetOf<String>()

    private var chatMode = ChatMode.Undecided
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
    private val webResultSystemInstruction: String by lazy { loadPrompt("web_result_system_instruction.txt") }
    private val guidedInstruction: String by lazy {
        loadPrompt("guided_instruction.txt")
            .replace("{{SWEETNESS}}", GuidedOptions.sweetness.joinToString())
            .replace("{{BODY}}", GuidedOptions.body.joinToString())
            .replace("{{TANNIN}}", GuidedOptions.tannin.joinToString())
            .replace("{{ACIDITY}}", GuidedOptions.acidity.joinToString())
    }

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
        when (chatMode) {
            ChatMode.Undecided -> handleModeChoice(query)
            ChatMode.Curious -> handleCuriousChat(query, onUpdate)
            ChatMode.FindWine -> handleFindWineTurn(query)
        }
    }

    private fun handleModeChoice(query: String): ChatMessage =
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
            chatMode = ChatMode.FindWine
            findWineStep = FindWineStep.Type
            chatPreferences = WinePreferences()
            declinedSteps.clear()
            return plainMessage("Sure — let's find you a wine.\n\n${ChatFlowText.questionFor(findWineStep)}")
        }

        suspend fun streamFrom(conversation: Conversation): String {
            val requestStartedAt = SystemClock.elapsedRealtime()
            var firstWordAt: Long? = null
            var lastWordAt = requestStartedAt
            var lastText = ""
            val response = buildString {
                conversation.sendMessageAsync(query).collect { message ->
                    message.contents.contents.filterIsInstance<Content.Text>().forEach { content ->
                        if (content.text.isNotEmpty()) {
                            val now = SystemClock.elapsedRealtime()
                            if (firstWordAt == null) firstWordAt = now
                            lastWordAt = now
                        }
                        append(content.text)
                        if (toString() != lastText) {
                            lastText = toString()
                            onUpdate(ConversationStreamUpdate(text = lastText))
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
            streamFrom(activeConversation)
        } catch (error: Throwable) {
            if (!error.isContextCapacityError()) throw error
            logFlow("Gemma context exhausted; rebuilding curious conversation and retrying turn once")
            withContext(Dispatchers.Default) {
                curiousConversation?.close()
                curiousConversation = null
            }
            streamFrom(ensureCuriousConversation())
        }
        val visible = response.withoutRepeatedConversationOpener(recordUsage = true)
        return plainMessage(visible.ifBlank { "Could you say a bit more about that?" })
    }

    private suspend fun handleFindWineTurn(query: String): ChatMessage {
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
        val prompt = timing.measure("prepare_preferences") { "Resolved preferences: ${preferences.toCompactJson()}" }
        timing.detail("input_characters", (instruction.length + prompt.length).toString())
        timing.detail("max_output_tokens", "1024")
        val searchConversation = timing.measure("create_conversation") {
            searchEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(instruction),
                    samplerConfig = SamplerConfig(topK = 30, topP = 0.85, temperature = 0.4),
                    maxOutputToken = 1_024,
                ),
            )
        }
        val completedCards = mutableListOf<WineSuggestion>()
        val cardStream = CompleteJsonObjects()
        fun acceptCard(payload: String) {
            if (completedCards.size >= RECOMMENDATION_COUNT) return
            val card = payload.toWineSuggestionOrNull() ?: return
            // Each published object is a finished profile; Unknown remains honest missing
            // information and must not trigger another model call when the card is opened.
            val profile = card.copy(profileComplete = true)
            if (preferences.cardMismatchReasons(
                    cardType = profile.wineType, cardCountry = profile.country,
                    cardProvince = profile.province, cardBody = profile.body,
                    cardTannin = profile.tannin, cardAcidity = profile.acidity,
                    cardSweetness = profile.sweetness, cardVariety = profile.variety,
                    cardFlavor = profile.preferenceFlavor, cardOccasion = profile.occasion,
                ).isNotEmpty()
            ) return
            if (profile.name.isBlank() || profile.name.equals("Unknown Wine", true) ||
                profile.name.looksLikeFieldNameEcho() || profile.name.looksLikeHallucinatedMarkup() ||
                profile.country.equals("Unknown", true) || profile.wineType.equals("Unknown", true)
            ) return
            if (completedCards.any {
                    it.name.equals(profile.name, true) && it.country.equals(profile.country, true) &&
                        it.province.equals(profile.province, true) && it.variety.equals(profile.variety, true)
                }
            ) return
            completedCards += profile
            timing.duration("card_${completedCards.size}_ready", SystemClock.elapsedRealtime() - requestStartedAt)
            onUpdate(ConversationStreamUpdate(text = "", suggestions = completedCards.toList()))
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
            firstWordAt?.let { timing.duration("first_to_last_output", lastWordAt - it) }
            timing.detail("output_characters", outputCharacters.toString())
            timing.detail("output_chunks", outputChunks.toString())
            timing.measure("close_conversation") { searchConversation.close() }
        }
        firstWordAt?.let { firstWord ->
            DebugLatencyLog.record("[Gemma] card search: time to first word", firstWord - requestStartedAt)
            DebugLatencyLog.record("[Gemma] card search: first word to last word", lastWordAt - firstWord)
        }
        val parsedSuggestions = timing.measure("parse_response") {
            extractSuggestions(response).take(RECOMMENDATION_COUNT)
        }
        val suggestions = timing.measure("validate_cards") {
            // Streaming and terminal messages must contain the same immutable profiles.
            completedCards.toList()
        }
        timing.detail("parsed_cards", parsedSuggestions.size.toString())
        timing.detail("usable_cards", suggestions.size.toString())
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

    private fun plainMessage(text: String): ChatMessage = ChatMessage(
        id = System.nanoTime(),
        author = MessageAuthor.Assistant,
        text = text,
    )

    suspend fun prepare() {
        val startedAt = SystemClock.elapsedRealtime()
        requestMutex.withLock { ensureEngine() }
        logFlow("Gemma prepared in ${SystemClock.elapsedRealtime() - startedAt}ms")
    }

    suspend fun guidedSelection(
        criteria: com.sheldondesousa.uncork.ui.guided.GuidedCriteria,
    ): List<WineSuggestion> = withContext(Dispatchers.Default) {
        require(criteria.valid)
        requestMutex.withLock {
            // Native inference must finish cleanup before the shared engine can be reused.
            // The UI ignores responses from superseded requests even if native work cannot stop.
            withContext(NonCancellable) {
                val requestStartedAt = SystemClock.elapsedRealtime()
                var firstWordAt: Long? = null
                var lastWordAt = requestStartedAt
                val guided = ensureEngine().createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(guidedInstruction),
                        samplerConfig = SamplerConfig(topK = 30, topP = 0.85, temperature = 0.35),
                        maxOutputToken = 1_024,
                    ),
                )
                val response = try {
                    buildString {
                        guided.sendMessageAsync(
                            "fixed_constraints: " + JSONObject(criteria.constraints()).toString(),
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
                    guided.close()
                }
                firstWordAt?.let { firstWord ->
                    DebugLatencyLog.record("[Gemma] guided selection: time to first word", firstWord - requestStartedAt)
                    DebugLatencyLog.record("[Gemma] guided selection: first word to last word", lastWordAt - firstWord)
                }
                val totalMs = SystemClock.elapsedRealtime() - requestStartedAt
                DebugLatencyLog.record("[Gemma] guided selection: total", totalMs)
                val suggestions = GuidedGemmaResponse.parse(response, criteria)
                logFlow("Gemma guided selection cards=${suggestions.size}, chars=${response.length}, totalMs=$totalMs")
                suggestions
            }
        }
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
                    samplerConfig = SamplerConfig(topK = 30, topP = 0.85, temperature = 0.35),
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
        engine?.close()
        engine = null
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

        private fun String.toWineSuggestionOrNull(): WineSuggestion? = runCatching {
            val json = JSONObject(trim())
            WineSuggestion(
                name = json.knownString("name", fallback = "Unknown Wine"),
                province = json.knownString("province"),
                country = json.knownString("country"),
                wineType = json.knownString("wine_type"),
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
            }

        private fun JSONObject.knownString(key: String, fallback: String = "Unknown"): String =
            optString(key).trim().trimEnd(':', ';', ',').trim()
                .takeIf { it.isNotEmpty() && !it.equals("null", true) } ?: fallback

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
            val array = optJSONArray(key) ?: return knownString(key)
            return buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }.takeIf(List<String>::isNotEmpty)?.joinToString(", ") ?: "Unknown"
        }

        private fun JSONObject.level(key: String): String {
            val value = knownString(key)
            val allowed = when (key) {
                "body" -> FindPhraseEvidence.BODY_LABELS
                "tannin" -> FindPhraseEvidence.TANNIN_LABELS
                "acidity" -> FindPhraseEvidence.ACIDITY_LABELS
                "sweetness" -> FindPhraseEvidence.SWEETNESS_EVIDENCE.keys.toList()
                else -> emptyList()
            }
            return allowed.firstOrNull { it.equals(value, ignoreCase = true) } ?: "Unknown"
        }

        private const val RECOMMENDATION_COUNT = 3
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
