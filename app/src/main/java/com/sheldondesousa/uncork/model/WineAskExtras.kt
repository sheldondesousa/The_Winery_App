package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.data.knowledge.GrapeProfileInternal
import com.sheldondesousa.uncork.data.knowledge.WineriesDirectory
import com.sheldondesousa.uncork.data.reviews.GrapeVarietyLookup
import com.sheldondesousa.uncork.data.reviews.VarietyCountryDigest

/** Extra context for one question in the Ask chat: added in front of the question only when the question needs it. */
interface AskExtras {
    /** Blocks to add before [query], or null when the question needs nothing extra. */
    suspend fun forQuestion(query: String): String?

    /** Forget what has been sent. Called when the conversation is rebuilt and so has lost earlier blocks. */
    fun reset()
}

/** Spots questions that ask what other people think, as opposed to questions about the open wine. */
object ReviewIntent {
    private val PATTERN = Regex(
        "\\b(reviews?|reviewers?|reviewed|critics?|enthusiasts?|opinions?|consensus)\\b|" +
            "\\bwhat\\s+(do|does|are|did|have)\\s+(people|folks|others|everyone|everybody|wine\\s+lovers|experts)\\b.*\\b(say|saying|said|think|thinking|thought)\\b|" +
            "\\bwhat\\s+(is|are)\\s+(people|the\\s+critics)\\s+saying\\b",
        RegexOption.IGNORE_CASE,
    )

    fun asksForReviews(query: String): Boolean = PATTERN.containsMatchIn(query)
}

/** Spots questions about wineries (where one is, or which wineries are somewhere). */
object WineryIntent {
    private val PATTERN = Regex(
        "\\b(wineries|winery|producers?|vineyards?|estates?|bodegas?|vignerons?|domaines?)\\b|" +
            "\\bwho\\s+(makes|produces|grows)\\b|\\b(located|based)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun asksAboutWineries(query: String): Boolean = PATTERN.containsMatchIn(query)
}

/**
 * Starts with only the open wine's own facts (already in the first message). Adds, in front of the question that needs
 * it: the broader review sample when the user asks what people say; notes for another grape that is in the grape notes
 * list; and, for a grape that is not in that list or for another country, what wine enthusiasts say in that country.
 * Each block is added once, since the conversation remembers it, and again after a rebuild.
 */
class WineAskExtras(
    private val knowledge: GrapeProfileInternal,
    /** Grapes already covered by the first message; not sent again. */
    private val ownGrapes: Set<String>,
    /** The open wine's grape (all its database spellings) and country, for questions that name no grape or no country. */
    private val ownVariety: List<String> = emptyList(),
    private val ownCountry: String = "",
    /** Region style rows for a grape's spellings in a country; empty when the app has none. */
    private val loadKaggleExtracted: suspend (spellings: List<String>, country: String) -> List<KaggleExtractedProfile> = { _, _ -> emptyList() },
    /** The Wineries_Directory, loaded when first needed; null if unavailable. */
    private val loadWineries: (suspend () -> WineriesDirectory?)? = null,
    private val loadReviews: suspend () -> VarietyCountryDigest?,
) : AskExtras {
    private val sentGrapes = ownGrapes.toMutableSet()
    private val sentKaggleExtracted = mutableSetOf<String>()
    private val sentWineries = mutableSetOf<String>()
    private var reviewsSent = false

    override suspend fun forQuestion(query: String): String? {
        val blocks = mutableListOf<String>()
        if (!reviewsSent && ReviewIntent.asksForReviews(query)) {
            val digest = runCatching { loadReviews() }.getOrNull()
            blocks += if (digest != null && !digest.isEmpty) {
                WineFactsNote.reviewsBlock(digest)
            } else {
                "WHAT WINE ENTHUSIASTS SAY\nThere are not enough reviews of this grape from this country to summarise."
            }
            reviewsSent = true
        }

        // 1. Grapes in the grape notes list come first, and are the trusted source for body, tannin and acidity.
        val inNotes = knowledge.findMentioned(query)
        val newNotes = inNotes.filter { it.grape !in sentGrapes }.take(MAX_GRAPES)
        if (newNotes.isNotEmpty()) {
            blocks += WineFactsNote.grapeBlock(newNotes)
            sentGrapes += newNotes.map { it.grape }
        }

        // 2. Grapes the notes list does not have: what enthusiasts say in the user's country, if the app has it.
        val countryNamed = CountryMentions.find(query).filterNot { it.equals(ownCountry, ignoreCase = true) }.firstOrNull()
        val country = countryNamed ?: ownCountry
        val inNotesNames = inNotes.map { it.grape }.toSet()
        val namedGrapes = GrapeVarietyLookup.findMentioned(query)
            .filter { grape -> grape.databaseNames.none { name -> knowledge.find(name).grapes.any { it.grape in inNotesNames || it.grape in ownGrapes } } }
            .filter { grape -> knowledge.find(grape.name.substringBefore(" / ")).grapes.isEmpty() }
        val regionTargets = buildList {
            namedGrapes.forEach { add(it.databaseNames to it.name) }
            // 3. Another country named for the open wine's grape: that country's style, attributed to enthusiasts.
            if (countryNamed != null && namedGrapes.isEmpty() && ownVariety.isNotEmpty()) add(ownVariety to ownVariety.first())
        }
        if (country.isNotBlank()) {
            regionTargets.take(MAX_GRAPES).forEach { (spellings, grape) ->
                val key = "${grape.lowercase()}|${country.lowercase()}"
                if (key in sentKaggleExtracted) return@forEach
                sentKaggleExtracted += key
                val styles = runCatching { loadKaggleExtracted(spellings, country) }.getOrNull().orEmpty()
                if (styles.isNotEmpty()) blocks += WineFactsNote.kaggleExtractedBlock(styles, grape)
            }
        }
        // 4. Wineries: a named winery's location, or a small sample of the wineries in a place the user names.
        val wineryLoader = loadWineries
        if (wineryLoader != null && WineryIntent.asksAboutWineries(query)) {
            val directory = runCatching { wineryLoader() }.getOrNull()
            if (directory != null) {
                // A country the user names is strict. The open wine's country is only a preference: a winery in
                // another country is still found if it is not listed in this one.
                val own = ownCountry.takeIf { it.isNotBlank() }
                val named = (countryNamed?.let { directory.findMentioned(query, it) }
                    ?: directory.findMentioned(query, own).ifEmpty { directory.findMentioned(query, null) })
                    .filter { "${it.winery}|${it.country}|${it.region}".lowercase() !in sentWineries }
                if (named.isNotEmpty()) {
                    sentWineries += named.map { "${it.winery}|${it.country}|${it.region}".lowercase() }
                    blocks += WineFactsNote.wineriesBlock(named, "entries for wineries named in the question")
                } else {
                    val region = directory.regionMentioned(query, countryNamed ?: ownCountry.takeIf { it.isNotBlank() })
                    val place = region ?: countryNamed?.let { it to "" }
                    val key = place?.let { "place|${it.first}|${it.second}".lowercase() }
                    if (place != null && key !in sentWineries) {
                        sentWineries += key!!
                        val sample = directory.sampleIn(place.first, place.second.takeIf { it.isNotBlank() })
                        if (sample.isNotEmpty()) {
                            val where = listOf(place.second, place.first).filter { it.isNotBlank() }.joinToString(", ")
                            blocks += WineFactsNote.wineriesBlock(
                                sample,
                                "a small, unranked sample of the ${directory.countOf(place.first, place.second.takeIf { it.isNotBlank() })} wineries listed in $where; not a complete list and not the best wineries",
                            )
                        }
                    }
                }
            }
        }
        return blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    override fun reset() {
        sentGrapes.clear()
        sentGrapes += ownGrapes
        sentKaggleExtracted.clear()
        sentWineries.clear()
        reviewsSent = false
    }

    private companion object {
        const val MAX_GRAPES = 2
    }
}
