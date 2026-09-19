package com.sheldondesousa.uncork.ui.conversation

import kotlinx.coroutines.delay

enum class MessageAuthor {
    User,
    Assistant,
}

enum class WineSuggestionSource {
    GEMMA,
    KAGGLE,
    WEB_SEARCH,
}

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
