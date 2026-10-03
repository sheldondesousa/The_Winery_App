package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.data.knowledge.GrapeKnowledge
import com.sheldondesousa.uncork.data.knowledge.GrapeLookup
import com.sheldondesousa.uncork.data.reviews.VarietyCountryDigest
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource

/** The typical style of a grape in one region, from the app's saved country/province/variety profiles. */
data class RegionStyleFacts(
    val body: String?,
    val tannin: String?,
    val acidity: String?,
    val flavorNotes: List<String>,
)

/**
 * Builds the "facts note" handed to Gemma when the user opens a conversation about one wine. Each block
 * is labelled with how far it can be trusted (see wine_discussion_instruction.txt): wine facts are
 * verified for this bottle, grape notes and region style describe the grape in general. Gemma's own
 * knowledge is the fourth, least trusted layer and is not part of the note.
 */
object WineFactsNote {
    private const val MAX_REVIEW_CHARS = 600
    private const val MAX_GRAPES = 3

    fun build(
        wine: WineSuggestion,
        grapes: GrapeLookup = GrapeLookup.NONE,
        region: RegionStyleFacts? = null,
        reviews: VarietyCountryDigest? = null,
    ): String = buildString {
        appendLine("WINE FACTS (verified for this bottle)")
        appendLine("Where this came from: ${wine.source.provenance()}")
        field("Name", wine.name)
        field("Winery", wine.winery.takeUnless { it == wine.name })
        field("Country", wine.country)
        field("Region", wine.province)
        field("Variety", wine.variety)
        field("Type", wine.wineType)
        wine.rating?.let { appendLine("Critic score: $it") }
        field("Body", wine.body)
        field("Tannin", wine.tannin)
        field("Acidity", wine.acidity)
        field("Sweetness", wine.sweetness)
        field("Flavour notes", wine.flavorNotes)
        field("Critic review", wine.reviewSummary.truncated())
        field("Web summary", wine.webSummary.truncated())

        if (grapes.grapes.isNotEmpty()) {
            appendLine()
            appendLine("GRAPE NOTES (about the grape in general, not this bottle)")
            grapes.blendNote?.let { appendLine(it) }
            grapes.grapes.take(MAX_GRAPES).forEach { appendGrape(it) }
        } else if (grapes.blendNote != null) {
            appendLine()
            appendLine("GRAPE NOTES")
            appendLine(grapes.blendNote)
        } else {
            appendLine()
            appendLine("GRAPE NOTES")
            appendLine("None available for this variety. Use general knowledge only if you are confident, and say so if not.")
        }

        if (region != null && (region.body != null || region.tannin != null || region.acidity != null || region.flavorNotes.isNotEmpty())) {
            appendLine()
            appendLine("REGION STYLE (typical pattern for this grape in this region, not this bottle)")
            field("Typical body", region.body)
            field("Typical tannin", region.tannin)
            field("Typical acidity", region.acidity)
            field("Typical flavours", region.flavorNotes.joinToString(", "))
        }

        if (reviews != null && !reviews.isEmpty) {
            appendLine()
            appendLine(
                "WHAT WINE ENTHUSIASTS SAY (an even sample of critic reviews of ${reviews.variety} from " +
                    "${reviews.country}: the same number from the highest-scored, middle and lowest-scored thirds. " +
                    "About the grape in that country, not this bottle)",
            )
            val average = reviews.averagePoints?.let { " average_score=\"${"%.1f".format(java.util.Locale.ROOT, it)}\"" }.orEmpty()
            appendLine("<reviews variety=\"${reviews.variety}\" country=\"${reviews.country}\" total=\"${reviews.totalReviews}\"$average>")
            reviews.bands.filter { it.reviews.isNotEmpty() }.forEach { band ->
                val range = if (band.minPoints != null && band.maxPoints != null) " points=\"${band.minPoints}-${band.maxPoints}\"" else ""
                appendLine("<band name=\"${band.name}\"$range sampled=\"${band.reviews.size}\" of=\"${band.poolSize}\">")
                band.reviews.forEach { review ->
                    val score = review.points?.let { ", $it" }.orEmpty()
                    appendLine("- [${review.province}$score] ${review.excerpt}")
                }
                appendLine("</band>")
            }
            appendLine("</reviews>")
        }
    }.trimEnd()

    /**
     * A few lines sent in front of every follow-up question. Gemma only reads the most recent few hundred
     * tokens closely, so this keeps the wine and the honesty rule close to each question instead of
     * leaving them far back in the first message.
     */
    fun reminder(wine: WineSuggestion): String {
        val place = listOf(wine.province, wine.country).filter(::isKnown).joinToString(", ")
        val about = buildString {
            append(wine.name.takeIf(::isKnown) ?: "this wine")
            val details = listOf(wine.variety.takeIf(::isKnown), place.takeIf { it.isNotBlank() }).filterNotNull()
            if (details.isNotEmpty()) append(" (${details.joinToString(", ")})")
        }
        return "[Reminder: we are talking about $about. State specifics about this bottle only if they are in WINE FACTS; " +
            "otherwise say you don't know. Stay excited and wine-only, and vary the wording that signals general knowledge.]"
    }

    private fun isKnown(value: String) = value.isNotBlank() && !value.equals("Unknown", ignoreCase = true)

    private fun StringBuilder.appendGrape(grape: GrapeKnowledge) {
        appendLine("- ${grape.grape} (${grape.colour.lowercase()}): ${grape.summary}")
        listOf(
            "Body" to grape.body, "Acidity" to grape.acidity, "Tannin" to grape.tannin,
            "Aromas" to grape.aromas,
            "Ageing" to grape.ageing, "Origin" to grape.origin, "Known regions" to grape.keyRegions,
        ).filter { it.second.isNotBlank() }
            .joinTo(this, separator = " | ", prefix = "  ") { "${it.first}: ${it.second}" }
        appendLine()
    }

    private fun StringBuilder.field(label: String, value: String?) {
        if (value.isNullOrBlank() || value.equals("Unknown", ignoreCase = true) ||
            value.equals("Not applicable", ignoreCase = true)
        ) return
        appendLine("$label: $value")
    }

    private fun String.truncated(): String =
        if (length <= MAX_REVIEW_CHARS) this else take(MAX_REVIEW_CHARS).trimEnd() + "…"

    private fun WineSuggestionSource.provenance(): String = when (this) {
        WineSuggestionSource.KAGGLE -> "reviewed-wines database (critic review)"
        WineSuggestionSource.CACHE -> "saved earlier from a web search"
        WineSuggestionSource.WEB_SEARCH -> "live web search; details were taken from search snippets and may be incomplete"
        WineSuggestionSource.GEMMA -> "suggested by the AI and not verified; do not state its details as fact"
    }
}
