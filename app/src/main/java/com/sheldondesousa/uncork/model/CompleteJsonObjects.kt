package com.sheldondesousa.uncork.model

/**
 * Incrementally extracts finished objects from the wine-card array.
 *
 * Gemma can occasionally emit malformed text containing an unmatched `{` before the requested
 * `[WINE_CARDS]` block. Starting at the first brace would then leave the parser permanently
 * nested and hide valid objects later in the response. Wait for the marker (or a raw JSON array
 * when the marker is omitted) before tracking object braces.
 */
internal class CompleteJsonObjects {
    private val preamble = StringBuilder()
    private val current = StringBuilder()
    private var started = false
    private var depth = 0
    private var inString = false
    private var escaped = false

    var startMode: String = "waiting"
        private set

    val unfinishedObjectDepth: Int get() = depth

    fun append(chunk: String): List<String> {
        val text = if (started) {
            chunk
        } else {
            preamble.append(chunk)
            val buffered = preamble.toString()
            val markerIndex = buffered.indexOf(WINE_CARDS_MARKER, ignoreCase = true)
            val rawArrayMatch = RAW_ARRAY_START.find(buffered)
            val contentStart = when {
                markerIndex >= 0 -> {
                    startMode = "marker"
                    markerIndex + WINE_CARDS_MARKER.length
                }
                rawArrayMatch != null -> {
                    startMode = "raw_array"
                    // The match ends at the first object-opening brace.
                    rawArrayMatch.range.last
                }
                else -> return emptyList()
            }
            started = true
            buffered.substring(contentStart).also { preamble.clear() }
        }

        return buildList {
            for (character in text) {
                if (depth == 0) {
                    if (character != '{') continue
                    depth = 1
                    current.append(character)
                    continue
                }
                current.append(character)
                if (inString) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> inString = false
                    }
                } else {
                    when (character) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                add(current.toString())
                                current.clear()
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val WINE_CARDS_MARKER = "[WINE_CARDS]"
        val RAW_ARRAY_START = Regex("\\[\\s*\\{")
    }
}
