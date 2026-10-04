package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.data.knowledge.InternalGrapeProfile
import com.sheldondesousa.uncork.data.knowledge.GrapeLookup
import com.sheldondesousa.uncork.data.knowledge.WineryLocation
import com.sheldondesousa.uncork.data.reviews.CountryReviewCount
import com.sheldondesousa.uncork.data.reviews.GrapeWineriesSample
import com.sheldondesousa.uncork.data.reviews.VarietyCountryDigest
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource

/**
 * The typical style of a grape in one region, from the app's saved country/province/variety profiles. These were
 * worked out from reviewer wording, so they are what wine enthusiasts say, not verified facts about the grape.
 */
data class KaggleExtractedProfile(
    val body: String?,
    val tannin: String?,
    val acidity: String?,
    val flavorNotes: List<String>,
    val province: String = "",
    val country: String = "",
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
        regionStyles: List<KaggleExtractedProfile> = emptyList(),
        reviews: VarietyCountryDigest? = null,
        winery: List<WineryLocation> = emptyList(),
        otherCountries: List<CountryReviewCount> = emptyList(),
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

        if (winery.isNotEmpty()) {
            appendLine()
            appendLine("Wineries_Directory (a reference list of wineries and their regions; not complete)")
            winery.forEach { appendLine("- ${it.winery}: ${it.region}, ${it.country}") }
        }

        if (grapes.grapes.isNotEmpty()) {
            appendLine()
            appendLine("Grape_Profile_Internal (about the grape in general, not this bottle)")
            grapes.blendNote?.let { appendLine(it) }
            grapes.grapes.take(MAX_GRAPES).forEach { appendGrape(it) }
        } else if (grapes.blendNote != null) {
            appendLine()
            appendLine("Grape_Profile_Internal")
            appendLine(grapes.blendNote)
        } else {
            appendLine()
            appendLine("Grape_Profile_Internal")
            appendLine("None available for this variety. Use general knowledge only if you are confident, and say so if not.")
        }

        // Order of trust for body, tannin and acidity: the grape notes first. Region style (what enthusiasts say) is
        // only given when there are no grape notes; if neither exists the model uses its own knowledge.
        if (grapes.grapes.isEmpty() && regionStyles.any { it.hasAnyValue() }) {
            appendLine()
            append(kaggleExtractedBlock(regionStyles, wine.variety))
            appendLine()
        }

        if (otherCountries.isNotEmpty()) {
            appendLine()
            appendLine(otherCountriesLine(otherCountries))
        }

        if (reviews != null && !reviews.isEmpty) {
            appendLine()
            append(reviewsBlock(reviews))
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

    private fun KaggleExtractedProfile.hasAnyValue() =
        !body.isNullOrBlank() || !tannin.isNullOrBlank() || !acidity.isNullOrBlank()

    /** "full*" means thin evidence in the saved profiles; say so in words and drop the asterisk. */
    private fun String.styleWords(): String =
        if (endsWith("*")) "${removeSuffix("*")} (limited evidence)" else this

    /**
     * Region style for a grape: what wine enthusiasts say about body, tannin and acidity in a country, one line per
     * province. The heading tells the model to credit enthusiasts and not state it as fact.
     */
    fun kaggleExtractedBlock(styles: List<KaggleExtractedProfile>, grape: String): String = buildString {
        val rows = styles.filter { it.hasAnyValue() }
        val country = rows.firstOrNull()?.country.orEmpty()
        val place = if (country.isNotBlank()) " in $country" else ""
        appendLine(
            "Grape_Profile_Kaggle_Extracted: what wine enthusiasts say about $grape$place (worked out from reviewer wording; NOT verified " +
                "grape facts, so credit enthusiasts when you use it, for example \"Wine enthusiasts say...\")",
        )
        rows.forEach { style ->
            val label = style.province.takeIf { it.isNotBlank() } ?: "Overall"
            val parts = buildList {
                style.body?.takeIf { it.isNotBlank() }?.let { add("body ${it.styleWords()}") }
                style.tannin?.takeIf { it.isNotBlank() }?.let { add("tannin ${it.styleWords()}") }
                style.acidity?.takeIf { it.isNotBlank() }?.let { add("acidity ${it.styleWords()}") }
                if (style.flavorNotes.isNotEmpty()) add("flavours ${style.flavorNotes.joinToString(", ")}")
            }
            appendLine("- $label: ${parts.joinToString("; ")}")
        }
    }.trimEnd()

    /** The even review sample, with a heading and tags. Sent only when the user asks what people say. */
    fun reviewsBlock(reviews: VarietyCountryDigest): String = buildString {
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
    }.trimEnd()

    /** The countries with the most reviews of a grape, for Gemma to offer after it answers. */
    fun otherCountriesLine(countries: List<CountryReviewCount>): String =
        "OTHER COUNTRIES with the most reviews of this grape (after you answer, offer to tell the user about the grape from one of these): " +
            countries.joinToString(", ") { "${it.country} (${it.reviewCount} reviews)" }

    /** Wineries that have reviews of a grape: a small unranked sample, never "the best". */
    fun grapeWineriesBlock(grape: String, country: String?, sample: GrapeWineriesSample): String = buildString {
        val where = country?.takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty()
        appendLine(
            "WINERIES WITH $grape REVIEWS (a small, unranked sample of the ${sample.totalWineries} wineries$where that have " +
                "reviews of this grape in the reviews database; not the best wineries and not a complete list)",
        )
        sample.wineries.forEach { appendLine("- ${it.winery} (${it.province}, ${it.country}; ${it.reviewCount} review${if (it.reviewCount == 1) "" else "s"})") }
    }.trimEnd()

    /** Entries from the Wineries_Directory, sent in front of a question about wineries. */
    fun wineriesBlock(wineries: List<WineryLocation>, what: String): String = buildString {
        appendLine("Wineries_Directory ($what; the directory is a reference list and is not complete)")
        wineries.forEach { appendLine("- ${it.winery}: ${it.region}, ${it.country}") }
    }.trimEnd()

    /** Notes for grapes the user asked about, sent in front of that question. */
    fun grapeBlock(grapes: List<InternalGrapeProfile>): String = buildString {
        appendLine("Grape_Profile_Internal (about the grape(s) the user just asked about, from the grape notes list)")
        grapes.forEach { appendGrape(it) }
    }.trimEnd()

    private fun StringBuilder.appendGrape(grape: InternalGrapeProfile) {
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
