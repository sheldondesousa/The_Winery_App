package com.sheldondesousa.uncork.ui.conversation

/**
 * The "Ask" conversation opened from a wine's detail page. It is not the Find-a-wine chat: it opens with a
 * warm welcome, says what the sommelier can help with, and invites an open discussion, with no mode choice.
 */
object WineAskChat {
    const val TITLE = "Ask"
    private const val WELCOME_ID = Long.MIN_VALUE + 1

    /** What the sommelier can help with, shown to the user. Food pairings are out of scope for now. */
    val TOPICS = listOf(
        "This wine",
        "Reviews",
        "Grape varieties",
        "Aromas and flavours",
        "Wine regions",
        "How wine is made, stored and served",
    )

    /**
     * The welcome text. Uses the chat's own light markup: the wine's name is `**bold**` (black) and
     * "I can help with:" is `%%burgundy bold%%`.
     */
    fun welcome(wineName: String): String {
        val name = wineName.replace("**", "").replace("##", "").replace("%%", "").trim()
        val subject = if (name.isNotBlank() && !name.equals("Unknown", ignoreCase = true)) "**$name**" else "this particular wine"
        return "Hi there! I'm Uncork, your AI sommelier, here to help you with questions about $subject " +
            "or something from the wine world.\n\n" +
            "%%I can help with:%%\n" +
            TOPICS.joinToString("\n") { "• $it" } +
            "\n\nWhat are you curious about?"
    }

    fun initialMessages(wineName: String): List<ChatMessage> = listOf(
        ChatMessage(id = WELCOME_ID, author = MessageAuthor.Assistant, text = welcome(wineName)),
    )
}
