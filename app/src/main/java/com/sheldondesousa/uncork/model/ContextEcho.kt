package com.sheldondesousa.uncork.model

/**
 * Gemma sometimes copies the app's own <more_context> wrapper into its reply, and even writes a made-up block of its
 * own. The wrapper is the app's private plumbing, never part of an answer, so it and everything inside it is removed.
 * A block that has been opened but not yet closed (while the reply is still streaming) is hidden too.
 */
object ContextEcho {
    private val CLOSED = Regex("<more_context>.*?</more_context>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val OPEN_TO_END = Regex("<more_context>.*\\z", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val STRAY_TAG = Regex("</?more_context>?", RegexOption.IGNORE_CASE)
    // The start of a tag still being typed, such as "<more_con", is hidden until it is complete.
    private val PARTIAL_TAG_AT_END = Regex("<(m(o(r(e(_(c(o(n(t(e(x(t)?)?)?)?)?)?)?)?)?)?)?)?\\z", RegexOption.IGNORE_CASE)

    fun strip(text: String): String = text
        .replace(CLOSED, "")
        .replace(OPEN_TO_END, "")
        .replace(STRAY_TAG, "")
        .replace(PARTIAL_TAG_AT_END, "")
        .replace(Regex("\\n{3,}"), "\n\n")
}
