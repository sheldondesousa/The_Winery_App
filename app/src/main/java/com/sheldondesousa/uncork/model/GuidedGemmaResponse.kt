package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.guided.GuidedCriteria
import com.sheldondesousa.uncork.ui.guided.GuidedOptions
import org.json.JSONObject

/** Strict envelope keeps a genuine empty response distinct from malformed model output. */
internal object GuidedGemmaResponse {
    private const val MAX_RECOMMENDATIONS = 3

    // A weak on-device model occasionally hallucinates a citation/footnote, e.g. "Domaine X
    // [75](https://en.wikipedia.org/...)" — a markdown link or bare URL is never a real wine name.
    private val MARKDOWN_OR_URL_PATTERN = Regex("\\[[^\\]]*]\\([^)]*\\)|https?://|www\\.")

    fun parse(response: String, criteria: GuidedCriteria): List<WineSuggestion> {
        val text = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        require(text.startsWith("{") && text.endsWith("}")) { "Malformed Gemma response" }
        val root = JSONObject(text)
        require(root.length() == 1 && root.has("recommendations"))
        val cards = root.getJSONArray("recommendations")
        require(cards.length() <= MAX_RECOMMENDATIONS)
        val suggestions = (0 until cards.length()).map { index -> cards.getJSONObject(index).toSuggestion(criteria) }
        return suggestions.distinctBy { it.name.lowercase() to it.variety to it.province to it.wineType }
    }

    private fun JSONObject.toSuggestion(criteria: GuidedCriteria): WineSuggestion {
        val card = this
        fun field(key: String): String {
            val value = card.get(key)
            require(value is String && value.isNotBlank()) { "Missing $key" }
            // A weak on-device model occasionally leaves a stray trailing punctuation mark
            // (e.g. "Merlot:") from the schema formatting instead of a clean value.
            return value.trim().trimEnd(':', ';', ',').trim()
        }
        criteria.constraints().forEach { (key, value) ->
            require(field(key).equals(value, ignoreCase = true)) { "Gemma contradicted $key" }
        }
        val name = field("name")
        require(!name.trimEnd('.').equals("name", ignoreCase = true)) {
            "Gemma echoed the name field instead of a real wine"
        }
        require(!MARKDOWN_OR_URL_PATTERN.containsMatchIn(name)) {
            "Gemma hallucinated markup instead of a real wine name"
        }
        val country = field("country")
        val province = field("province")
        val variety = field("variety")
        val wineType = field("wine_type")
        require(wineType.lowercase() in listOf("red", "white", "sparkling", "rosé", "fortified", "unknown"))
        val sweetness = field("sweetness")
        require((GuidedOptions.sweetness + "Unknown").any { it.equals(sweetness, true) })
        val body = field("body")
        val tannin = field("tannin")
        val acidity = field("acidity")
        require((GuidedOptions.body + "Unknown").any { it.equals(body, true) })
        require((GuidedOptions.tannin + "Unknown").any { it.equals(tannin, true) })
        require((GuidedOptions.acidity + "Unknown").any { it.equals(acidity, true) })
        val summary = field("summary")
        require(summary.length < 200)
        val notes = card.getJSONArray("flavor_notes")
        require(notes.length() in 2..4)
        val flavors = (0 until notes.length()).map { index ->
            val note = notes.get(index)
            require(note is String && note.isNotBlank())
            note.trim()
        }
        return WineSuggestion(
            name = name, winery = "Unknown",
            country = country, province = province, variety = variety, wineType = wineType,
            body = body, tannin = tannin, acidity = acidity, sweetness = sweetness,
            flavorNotes = flavors.joinToString(", "), summary = summary,
            requestContext = criteria.description, profileComplete = true,
        )
    }
}
