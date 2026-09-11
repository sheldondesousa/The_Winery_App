package com.sheldondesousa.uncork.ui.history

import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import java.util.Locale

object RequestKeywordExtractor {
    private data class Rule(val label: String, val variants: List<String>)
    private data class Match(val index: Int, val label: String)

    fun extract(request: String, suggestion: WineSuggestion): List<String> {
        val normalizedRequest = request.lowercase(Locale.ROOT)
        val matches = buildList {
            addAll(dynamicWineTerms(suggestion).mapNotNull { term ->
                normalizedRequest.findPhrase(term)?.let { Match(it, term) }
            })
            addAll(RULES.mapNotNull { rule ->
                rule.variants.mapNotNull { variant -> normalizedRequest.findPhrase(variant) }.minOrNull()?.let {
                    Match(it, rule.label)
                }
            })
        }

        return matches
            .sortedBy(Match::index)
            .distinctBy { it.label.lowercase(Locale.ROOT) }
            .map(Match::label)
            .take(MAX_KEYWORDS)
            .ifEmpty { listOf(DEFAULT_KEYWORD) }
    }

    private fun dynamicWineTerms(suggestion: WineSuggestion): List<String> = buildList {
        add(suggestion.name)
        add(suggestion.winery)
        add(suggestion.variety)
        add(suggestion.region)
        addAll(suggestion.region.split(',').map(String::trim))
    }.filter { it.isUsefulValue() }.distinctBy { it.lowercase(Locale.ROOT) }

    private fun String.findPhrase(phrase: String): Int? {
        val target = phrase.trim().lowercase(Locale.ROOT)
        if (target.isBlank()) return null
        var fromIndex = 0
        while (fromIndex <= length - target.length) {
            val index = indexOf(target, fromIndex)
            if (index < 0) return null
            val beforeIsBoundary = index == 0 || !this[index - 1].isLetterOrDigit()
            val end = index + target.length
            val afterIsBoundary = end == length || !this[end].isLetterOrDigit()
            if (beforeIsBoundary && afterIsBoundary) return index
            fromIndex = index + 1
        }
        return null
    }

    private fun String.isUsefulValue(): Boolean =
        isNotBlank() && !equals("Unknown", ignoreCase = true)

    private const val MAX_KEYWORDS = 8
    private const val DEFAULT_KEYWORD = "Wine suggestion"

    private val RULES = listOf(
        Rule("Red", listOf("red wine", "red")),
        Rule("White", listOf("white wine", "white")),
        Rule("Rosé", listOf("rosé", "rose wine")),
        Rule("Sparkling", listOf("sparkling wine", "sparkling", "bubbly")),
        Rule("Orange", listOf("orange wine")),
        Rule("Dessert wine", listOf("dessert wine")),
        Rule("Natural wine", listOf("natural wine")),
        Rule("Light body", listOf("light-bodied", "light bodied", "light body")),
        Rule("Medium body", listOf("medium-bodied", "medium bodied", "medium body")),
        Rule("Full body", listOf("full-bodied", "full bodied", "full body")),
        Rule("Low acidity", listOf("low acidity", "low acid")),
        Rule("Medium acidity", listOf("medium acidity", "moderate acidity")),
        Rule("High acidity", listOf("high acidity", "high acid", "acidic", "crisp", "bright")),
        Rule("Low tannin", listOf("low tannin", "low tannins", "soft tannin", "soft tannins")),
        Rule("Medium tannin", listOf("medium tannin", "medium tannins", "moderate tannin", "moderate tannins")),
        Rule("High tannin", listOf("high tannin", "high tannins", "tannic")),
        Rule("Dry", listOf("bone dry", "dry")),
        Rule("Off-dry", listOf("off-dry", "off dry")),
        Rule("Sweet", listOf("sweet", "sweet wine")),
        Rule("Fruity", listOf("fruit-forward", "fruit forward", "fruity")),
        Rule("Earthy", listOf("earthy")),
        Rule("Oaky", listOf("oaky", "oak-aged", "oak aged", "oak")),
        Rule("Smoky", listOf("smoky", "smoke")),
        Rule("Spicy", listOf("spicy", "peppery", "spice")),
        Rule("Floral", listOf("floral", "flowers")),
        Rule("Citrus", listOf("citrus", "lemon", "lime", "grapefruit")),
        Rule("Berry", listOf("berries", "berry", "blackberry", "raspberry", "strawberry")),
        Rule("Cherry", listOf("cherry", "cherries")),
        Rule("Plum", listOf("plum", "plummy")),
        Rule("Vanilla", listOf("vanilla")),
        Rule("Chocolate", listOf("chocolate", "cocoa")),
        Rule("Mineral", listOf("mineral", "minerality")),
        Rule("Herbal", listOf("herbal", "herbaceous", "herbs")),
        Rule("France", listOf("france", "french")),
        Rule("Italy", listOf("italy", "italian")),
        Rule("Spain", listOf("spain", "spanish")),
        Rule("Portugal", listOf("portugal", "portuguese")),
        Rule("Germany", listOf("germany", "german")),
        Rule("Austria", listOf("austria", "austrian")),
        Rule("United States", listOf("united states", "american", "usa", "u.s.")),
        Rule("Argentina", listOf("argentina", "argentinian")),
        Rule("Chile", listOf("chile", "chilean")),
        Rule("Australia", listOf("australia", "australian")),
        Rule("New Zealand", listOf("new zealand")),
        Rule("South Africa", listOf("south africa", "south african")),
        Rule("India", listOf("india", "indian")),
    )
}
