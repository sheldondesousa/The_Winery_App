package com.sheldondesousa.uncork.ui.conversation

import com.sheldondesousa.uncork.model.WinePreferences
import kotlinx.coroutines.delay

enum class MessageAuthor {
    User,
    Assistant,
}

enum class WineSuggestionSource {
    GEMMA,
    KAGGLE,
    CACHE,
    WEB_SEARCH,
}

enum class SourceQueryStatus { LOADING, COMPLETE, FAILED }

/**
 * One source's outcome for the current turn — cards if it found any, an empty list if it ran
 * and came up with nothing (still worth showing, not silently skipped), or [LOADING] while it's
 * still in flight. A [ChatMessage]'s [ChatMessage.sourceResults] lists these in the order each
 * source actually resolved, not a fixed source order.
 */
data class SourceResult(
    val source: WineSuggestionSource,
    val status: SourceQueryStatus,
    val suggestions: List<WineSuggestion> = emptyList(),
)

data class ChatMessage(
    val id: Long,
    val author: MessageAuthor,
    val text: String,
    val suggestion: WineSuggestion? = null,
    val suggestions: List<WineSuggestion> = emptyList(),
    val historyRequest: String? = null,
    val followUpText: String? = null,
    val needsClarification: Boolean = false,
    val stageOneOutput: Boolean = false,
    val coverageComplete: Boolean = false,
    val discardedStageOneCards: Int = 0,
    val quickReplies: List<String> = emptyList(),
    // Time from request start to the first streamed word, debug builds only. Never set from
    // model output, not persisted, not shown to real users.
    val debugLatencyMs: Long? = null,
    // Set only when a deterministic Q1-Q3 chat turn just finalized the user's preferences,
    // before Gemma's card-synthesis call has run. Lets a wrapping responder start a Kaggle
    // lookup from these recorded answers at the same time as (not after) the Gemma call.
    val resolvedPreferences: WinePreferences? = null,
    // Per-source breakdown for a preferences-driven search turn (Kaggle/cache/web/Gemma), in the
    // order each source actually resolved. Empty for turns that don't run this multi-source
    // search (e.g. plain chat replies, the Q1-Q3 questions themselves).
    val sourceResults: List<SourceResult> = emptyList(),
)

data class WineSuggestion(
    val name: String,
    val province: String,
    val country: String = "Unknown",
    val wineType: String = "Unknown",
    val winery: String = name,
    val variety: String = name,
    val sweetness: String = "Unknown",
    val body: String = "Unknown",
    val tannin: String = "Unknown",
    val acidity: String = "Unknown",
    val flavorNotes: String = "Unknown",
    val preferenceFlavor: String = "Unknown",
    val occasion: String = "Unknown",
    val suggestedPairing: String = "Unknown",
    val summary: String = "Unknown",
    // Critic score from a genuine Kaggle `points` value. Must never be set from model output.
    val rating: Int? = null,
    val reviewSummary: String = "Unknown",
    val webSummary: String = "Unknown",
    val source: WineSuggestionSource = WineSuggestionSource.GEMMA,
    val requestContext: String? = null,
    val profileComplete: Boolean = false,
    val favoriteRating: Int? = null,
    val isFavorite: Boolean = false,
)

val ChatMessage.wineSuggestions: List<WineSuggestion>
    get() = suggestions.ifEmpty { listOfNotNull(suggestion) }

data class ConversationStreamUpdate(
    val text: String,
    val suggestions: List<WineSuggestion> = emptyList(),
    val sourceResults: List<SourceResult> = emptyList(),
    // Set once Kaggle/cache have settled, to surface the web-search follow-up question mid-turn
    // instead of waiting for Gemma (which can still be mid-inference) before the turn completes.
    val followUpText: String? = null,
)

fun interface ConversationResponder {
    suspend fun replyTo(query: String): ChatMessage

    suspend fun replyToStreaming(
        query: String,
        onPartialText: (String) -> Unit,
    ): ChatMessage = replyTo(query)

    suspend fun replyToUpdates(
        query: String,
        onUpdate: (ConversationStreamUpdate) -> Unit,
    ): ChatMessage = replyToStreaming(query) { text ->
        onUpdate(ConversationStreamUpdate(text = text))
    }
}

/**
 * Implemented by responders that can synthesize wine cards for an already-resolved
 * [WinePreferences] outside the normal turn-by-turn [ConversationResponder] flow — letting a
 * caller kick this off at the same time as an independent lookup driven by the same
 * preferences, rather than waiting for it to finish first.
 */
fun interface WineCardSynthesizer {
    suspend fun synthesizeCards(
        preferences: WinePreferences,
        onUpdate: (ConversationStreamUpdate) -> Unit,
    ): ChatMessage
}

/**
 * Development-only responder used until Gemma inference is connected. Keeping it behind an
 * interface lets the conversation UI remain unchanged when the real model arrives.
 */
class DemoConversationResponder : ConversationResponder {
    override suspend fun replyTo(query: String): ChatMessage {
        delay(650)
        return ChatMessage(
            id = System.nanoTime(),
            author = MessageAuthor.Assistant,
            text = "A bright Pinot Noir could work beautifully here — enough red fruit to feel generous, with the freshness to keep the pairing lively.",
            suggestion = WineSuggestion(
                name = "Pinot Noir",
                province = "Willamette Valley, Oregon",
            ),
        )
    }
}
