package com.sheldondesousa.uncork.model

/** Incrementally extracts finished JSON objects, respecting braces and escapes in strings. */
internal class CompleteJsonObjects {
    private val current = StringBuilder()
    private var depth = 0
    private var inString = false
    private var escaped = false

    fun append(chunk: String): List<String> = buildList {
        for (character in chunk) {
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
