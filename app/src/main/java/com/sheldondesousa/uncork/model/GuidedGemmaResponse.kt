package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.guided.GuidedCriteria
import com.sheldondesousa.uncork.ui.guided.GuidedOptions
import org.json.JSONObject

/** Strict envelope keeps a genuine empty response distinct from malformed model output. */
internal object GuidedGemmaResponse {
    private const val MAX_RECOMMENDATIONS = 3

    fun parse(response: String, criteria: GuidedCriteria): List<WineSuggestion> {
        val text = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        require(text.startsWith("{") && text.endsWith("}")) { "Malformed Gemma response" }
        val root = JSONObject(text)
        require(root.length() == 1 && root.has("recommendations"))
        val cards = root.getJSONArray("recommendations")
        require(cards.length() <= MAX_RECOMMENDATIONS)
        val suggestions = (0 until cards.length()).map { index -> cards.getJSONObject(index).toSuggestion(criteria) }
        return suggestions.distinctBy { it.variety to it.province to it.wineType }
    }

    private fun JSONObject.toSuggestion(criteria: GuidedCriteria): WineSuggestion {
        val card = this
        fun field(key: String): String {
            val value = card.get(key)
            require(value is String && value.isNotBlank()) { "Missing $key" }
            return value.trim()
        }
        criteria.constraints().forEach { (key, value) ->
            require(value.any { field(key).equals(it, ignoreCase = true) }) { "Gemma contradicted $key" }
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
            name = listOf(variety, province).filterNot { it.equals("Unknown", true) }
                .joinToString(" · ").ifBlank { "$wineType wine" }, winery = "Unknown",
            country = country, province = province, variety = variety, wineType = wineType,
            body = body, tannin = tannin, acidity = acidity, sweetness = sweetness,
            flavorNotes = flavors.joinToString(", "), summary = summary,
            requestContext = criteria.description, profileComplete = true,
        )
    }
}
