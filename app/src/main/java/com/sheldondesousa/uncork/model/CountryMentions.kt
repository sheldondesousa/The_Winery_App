package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.ui.guided.WineRegions
import java.text.Normalizer

/** Finds the countries a user names in a question, using the same names as the review database and Find. */
object CountryMentions {
    // Words people use for a country, other than its name. Every country in the winery directory and the reviews has its
    // adjective here. "us" is not in this list (it is also a plain word); see [US_CAPITALS] and [US_BESIDE_WINE_WORDS].
    private val ADJECTIVES = mapOf(
        "french" to "France", "italian" to "Italy", "spanish" to "Spain", "portuguese" to "Portugal",
        "german" to "Germany", "austrian" to "Austria", "australian" to "Australia", "american" to "United States",
        "america" to "United States", "united states of america" to "United States",
        "usa" to "United States", "argentine" to "Argentina", "argentinian" to "Argentina", "chilean" to "Chile",
        "south african" to "South Africa", "greek" to "Greece", "hungarian" to "Hungary", "british" to "United Kingdom",
        "britain" to "United Kingdom", "great britain" to "United Kingdom",
        "uk" to "United Kingdom", "england" to "United Kingdom", "kiwi" to "New Zealand", "new zealander" to "New Zealand",
        "armenian" to "Armenia", "azerbaijani" to "Azerbaijan", "belgian" to "Belgium", "bosnian" to "Bosnia and Herzegovina",
        "brazilian" to "Brazil", "bulgarian" to "Bulgaria", "canadian" to "Canada", "chinese" to "China", "croatian" to "Croatia",
        "cypriot" to "Cyprus", "czech" to "Czech Republic", "egyptian" to "Egypt", "estonian" to "Estonia", "georgian" to "Georgia",
        "indian" to "India", "indonesian" to "Indonesia", "israeli" to "Israel", "latvian" to "Latvia", "lebanese" to "Lebanon",
        "lithuanian" to "Lithuania", "luxembourgish" to "Luxembourg", "macedonian" to "Macedonia", "mexican" to "Mexico",
        "moldovan" to "Moldova", "moroccan" to "Morocco", "norwegian" to "Norway", "peruvian" to "Peru", "romanian" to "Romania",
        "serbian" to "Serbia", "slovak" to "Slovakia", "slovenian" to "Slovenia", "swedish" to "Sweden", "swiss" to "Switzerland",
        "turkish" to "Turkey", "ukrainian" to "Ukraine", "uruguayan" to "Uruguay", "venezuelan" to "Venezuela",
    )

    private val EXTRA_COUNTRIES = listOf(
        "Bosnia and Herzegovina", "Czech Republic", "Lebanon", "Luxembourg", "Macedonia", "Moldova", "Morocco", "Slovenia",
        "Egypt", "Estonia", "Indonesia", "Israel", "Latvia", "Lithuania", "Norway", "Sweden",
    )

    // "US", "U.S.", "USA", "U.S.A." written in capitals; matched on the original text, case-sensitively.
    private val US_CAPITALS = Regex("\\bU\\.?S\\.?(?:A\\.?)?(?![A-Za-z])")

    // A lower-case "us" counts only beside a wine word ("us wineries", "wines from us", "in the us"), never in "tell us" or "focus".
    private val US_BESIDE_WINE_WORDS = Regex(
        "\\bus\\s+(wineries|winery|wines?|producers?|vineyards?|estates?|reds?|whites?)\\b|" +
            "\\b(wineries|wines?|producers?|vineyards?|estates?)\\s+(in|from|of)\\s+(the\\s+)?us\\b|\\bin\\s+the\\s+us\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun namesUnitedStates(text: String) = US_CAPITALS.containsMatchIn(text) || US_BESIDE_WINE_WORDS.containsMatchIn(text)

    /** The adjective for a country ("French" for France), for writing a question back out; null when there is none. */
    fun adjectiveFor(country: String): String? =
        ADJECTIVES.entries.firstOrNull { it.value == country && it.key !in setOf("usa", "uk", "england") }?.key
            ?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private val byName: Map<String, String> by lazy {
        buildMap {
            WineRegions.catalog.keys.forEach { put(normalize(it), it) }
            // Countries the winery directory has but Find's list does not.
            EXTRA_COUNTRIES.forEach { put(normalize(it), it) }
            putAll(ADJECTIVES)
        }
    }

    private const val MAX_WORDS = 4

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
        if (namesUnitedStates(text)) found += "United States"
        return found.toList()
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
