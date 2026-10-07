package com.sheldondesousa.uncork.model

/**
 * A last check on what Gemma wrote, before the user sees it. A small on-device model can slip characters from another
 * writing system into an English reply, break a reply into several paragraphs or lists, or run long.
 */
object ReplyCheck {
    /** The longest reply shown, in characters: roughly the 2 to 4 short sentences the instructions ask for, with room to spare. */
    const val MAX_CHARS = 800

    private val MARKDOWN = Regex("(\\*\\*|__|(?m)^\\s*(#{1,6}\\s|[-*\u2022]\\s|\\d+[.)]\\s)|[\\[\\]{}])")

    /**
     * True when [text] holds a letter from any writing system other than Latin (Greek, Cyrillic, Thai, Khmer, Hebrew,
     * Arabic, Chinese/Japanese/Korean, Devanagari and so on). Accented Latin letters, digits and punctuation are fine.
     */
    fun hasForeignScript(text: String): Boolean = text.codePoints().anyMatch { cp ->
        val script = Character.UnicodeScript.of(cp)
        script != Character.UnicodeScript.LATIN && script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED
    }

    /** True when the reply is not one plain-text paragraph within the length limit. */
    fun hasFormattingProblem(text: String): Boolean =
        text.contains('\n') || MARKDOWN.containsMatchIn(text) || text.length > MAX_CHARS

    private val OPINION_WORDS = listOf("famous", "finest", "renowned", "best", "world-class", "world class", "legendary", "iconic")

    /** Opinion words in [reply] that appear nowhere in [context] (the notes Gemma was given): praise the notes never made. */
    fun unsupportedOpinionWords(reply: String, context: String): List<String> {
        val have = context.lowercase()
        return OPINION_WORDS.filter { word ->
            Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(reply) &&
                !Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(have)
        }
    }

    /**
     * An answer built from the notes themselves, word for word: the first one or two sentences of the production notes'
     * first fact, else of the grape notes' summary. Null when [context] holds neither.
     */
    fun factsFallback(context: String): String? {
        val lines = context.lines()
        fun sentences(text: String) = text.trim().split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ")
        val production = lines.indexOfFirst { it.startsWith("Wine_Production") }
        if (production >= 0) {
            lines.drop(production + 1).firstOrNull { it.startsWith("- ") }?.let { line ->
                val value = line.substringAfter("]: ", "").substringBefore(" (general to ")
                if (value.isNotBlank()) return sentences(value)
            }
        }
        val grape = lines.indexOfFirst { it.startsWith("Grape_Profile_Internal") }
        if (grape >= 0) {
            lines.drop(grape + 1).firstOrNull { it.startsWith("- ") }?.let { line ->
                val value = line.substringAfter("): ", "")
                if (value.isNotBlank()) return sentences(value)
            }
        }
        return null
    }

    /** True when the reply should be asked for again. */
    fun needsRetry(text: String): Boolean = hasForeignScript(text) || hasFormattingProblem(text)

    /** One plain paragraph within the limit: lists and markdown marks removed, line breaks turned into spaces, cut at a sentence end. */
    fun tidy(text: String): String {
        var out = MARKDOWN.replace(text, " ").replace(Regex("\\s+"), " ").trim()
        if (out.length > MAX_CHARS) {
            val head = out.take(MAX_CHARS)
            val end = head.lastIndexOfAny(charArrayOf('.', '!', '?'))
            out = if (end >= MAX_CHARS / 3) head.take(end + 1) else head.substringBeforeLast(' ').trimEnd(',', ';', ':') + "\u2026"
        }
        return out
    }
}
