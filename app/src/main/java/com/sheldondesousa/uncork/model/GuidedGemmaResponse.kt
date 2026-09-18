package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.guided.GuidedCriteria
import com.sheldondesousa.uncork.ui.guided.GuidedOptions
import org.json.JSONObject

/** Strict envelope keeps a genuine empty response distinct from malformed model output. */
internal object GuidedGemmaResponse {
    val instruction = """
        You are an on-device wine guide. Return ONLY a JSON object with one key, "recommendations",
        containing zero or one objects. An empty array means no plausible recommendation.
        The supplied fixed_constraints contain allowed-value lists. Match ONE value from each selected
        field (OR within a field, AND across fields). Never change or broaden those lists.
        Each recommendation must contain wine_type, country, province, variety, sweetness, body, tannin, acidity,
        flavor_notes (an array of 2-4 short strings), and summary (a string under 200 characters).
        Only selected criteria are fixed; absent fields are unrestricted. Choose a plausible wine
        category and variety compatible with every selected criterion, without imposing defaults
        for omitted fields. Return a single string chosen from the allowed list for each fixed field. For other fields,
        provide plausible typical characteristics or "Unknown". wine_type must be Red, White,
        Sparkling, Rosé, Fortified, or Unknown. Sweetness uses ${GuidedOptions.sweetness.joinToString()}, or Unknown.
        Body uses ${GuidedOptions.body.joinToString()}, or Unknown.
        Tannin uses ${GuidedOptions.tannin.joinToString()}, or Unknown.
        Acidity uses ${GuidedOptions.acidity.joinToString()}, or Unknown. Values are model-generated,
        not verified bottle facts. Recommend a wine category, not an invented bottle or producer.
        Do not supply winery, vintage, critic score, review_summary, or web_summary.
    """

    fun parse(response: String, criteria: GuidedCriteria): List<WineSuggestion> {
        val text = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        require(text.startsWith("{") && text.endsWith("}")) { "Malformed Gemma response" }
        val root = JSONObject(text)
        require(root.length() == 1 && root.has("recommendations"))
        val cards = root.getJSONArray("recommendations")
        require(cards.length() <= 1)
        if (cards.length() == 0) return emptyList()
        val card = cards.getJSONObject(0)
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
        return listOf(WineSuggestion(
            name = listOf(variety, province).filterNot { it.equals("Unknown", true) }
                .joinToString(" · ").ifBlank { "$wineType wine" }, winery = "Unknown",
            country = country, province = province, variety = variety, wineType = wineType,
            body = body, tannin = tannin, acidity = acidity, sweetness = sweetness,
            flavorNotes = flavors.joinToString(", "), summary = summary,
            requestContext = criteria.description, profileComplete = true,
        ))
    }
}
