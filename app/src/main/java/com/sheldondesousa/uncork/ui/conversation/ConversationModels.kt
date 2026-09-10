package com.sheldondesousa.uncork.ui.conversation

import kotlinx.coroutines.delay

enum class MessageAuthor {
    User,
    Assistant,
}

data class ChatMessage(
    val id: Long,
    val author: MessageAuthor,
    val text: String,
    val suggestion: WineSuggestion? = null,
)

data class WineSuggestion(
    val name: String,
    val region: String,
    val winery: String = name,
    val variety: String = name,
    val body: String = "Unknown",
    val tannin: String = "Unknown",
    val acidity: String = "Unknown",
    val flavorNotes: String = "Unknown",
    val sourceRating: String = "Unknown",
    val confidencePercent: Int? = null,
    val favoriteRating: Int? = null,
    val isFavorite: Boolean = false,
)

fun interface ConversationResponder {
    suspend fun replyTo(query: String): ChatMessage
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
                region = "Willamette Valley, Oregon",
            ),
        )
    }
}
