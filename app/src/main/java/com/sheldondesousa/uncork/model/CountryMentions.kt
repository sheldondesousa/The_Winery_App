package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.ui.guided.WineRegions
import java.text.Normalizer

/** Finds the countries a user names in a question, using the same names as the review database and Find. */
object CountryMentions {
    private val ADJECTIVES = mapOf(
        "french" to "France", "italian" to "Italy", "spanish" to "Spain", "portuguese" to "Portugal",
        "german" to "Germany", "austrian" to "Austria", "australian" to "Australia", "american" to "United States",
        "usa" to "United States", "argentine" to "Argentina", "argentinian" to "Argentina", "chilean" to "Chile",
        "south african" to "South Africa", "greek" to "Greece", "hungarian" to "Hungary", "british" to "United Kingdom",
        "uk" to "United Kingdom", "england" to "United Kingdom", "kiwi" to "New Zealand",
    )

    private val byName: Map<String, String> by lazy {
        buildMap {
            WineRegions.catalog.keys.forEach { put(normalize(it), it) }
            putAll(ADJECTIVES)
        }
    }

    private const val MAX_WORDS = 2

    fun find(text: String): List<String> {
        val tokens = normalize(text).split(' ').filter { it.isNotBlank() }
        val used = BooleanArray(tokens.size)
        val found = linkedSetOf<String>()
        for (length in MAX_WORDS downTo 1) {
            for (start in 0..tokens.size - length) {
                if ((start until start + length).any { used[it] }) continue
                val country = byName[tokens.subList(start, start + length).joinToString(" ")] ?: continue
                (start until start + length).forEach { used[it] = true }
                found += country
            }
        }
        return found.toList()
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
