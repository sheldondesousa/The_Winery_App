package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.data.knowledge.GrapeProfileInternal
import com.sheldondesousa.uncork.data.knowledge.WineriesDirectoryProvider
import com.sheldondesousa.uncork.data.profile.VarietyRegionProfileRepository
import com.sheldondesousa.uncork.data.reviews.GrapeVarieties
import com.sheldondesousa.uncork.data.reviews.WineReviewDataSource
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion

/** Gathers the three knowledge layers for one wine and turns them into the facts note Gemma is given. */
class WineAskContext(
    private val knowledge: GrapeProfileInternal,
    private val profiles: VarietyRegionProfileRepository,
    private val reviews: WineReviewDataSource,
    private val wineries: WineriesDirectoryProvider? = null,
) {
    suspend fun factsFor(wine: WineSuggestion): String {
        val spellings = spellingsOf(wine.variety)
        // Only wines with a real winery name are looked up (a card's winery defaults to its own name when unknown).
        val winery = if (wine.winery.isUnknown() || wine.winery == wine.name) emptyList() else {
            runCatching { wineries?.get()?.find(wine.winery, wine.country) }.getOrNull().orEmpty()
        }
        val grapes = knowledge.find(wine.variety)
        // Region style is only a fallback for the grape notes, so it is only looked up when there are none.
        val regionStyles = if (grapes.grapes.isNotEmpty() || wine.country.isUnknown()) emptyList() else {
            kaggleExtractedFor(spellings, wine.country, preferredProvince = wine.province)
        }
        val otherCountries = if (wine.country.isUnknown() || wine.variety.isUnknown()) emptyList() else {
            runCatching { reviews.topCountriesFor(spellings, excludeCountry = wine.country) }.getOrNull().orEmpty()
        }
        // The broader review sample is not part of the first message; see [extrasFor].
        return WineFactsNote.build(wine, grapes, regionStyles, winery = winery, otherCountries = otherCountries)
    }

    /**
     * Context added to later questions: the broader review sample when the user asks what people say, and notes for
     * other grapes they name. The open wine's own grape is already in the first message.
     */
    fun extrasFor(wine: WineSuggestion): AskExtras {
        val spellings = spellingsOf(wine.variety)
        return WineAskExtras(
            knowledge = knowledge,
            ownGrapes = knowledge.find(wine.variety).grapes.map { it.grape }.toSet(),
            ownVariety = if (wine.variety.isUnknown()) emptyList() else spellings,
            ownCountry = wine.country.takeUnless { it.isUnknown() }.orEmpty(),
            loadKaggleExtracted = { grapeSpellings, country -> kaggleExtractedFor(grapeSpellings, country, preferredProvince = "") },
            loadWineries = wineries?.let { provider -> { provider.get() } },
            loadOtherCountries = { grapeSpellings, country -> otherCountriesFor(grapeSpellings, country) },
            loadGrapeWineries = { grapeSpellings, country -> grapeWineries(grapeSpellings, country) },
            loadReviews = { grapeSpellings, country -> reviewSample(grapeSpellings, country) },
        )
    }

    /**
     * Extras for Chat's open conversation. There is no open wine, so the grape and country come from the question.
     */
    fun extrasForChat(): AskExtras = WineAskExtras(
        knowledge = knowledge,
        loadKaggleExtracted = { grapeSpellings, country -> kaggleExtractedFor(grapeSpellings, country, preferredProvince = "") },
        loadWineries = wineries?.let { provider -> { provider.get() } },
        loadOtherCountries = { grapeSpellings, country -> otherCountriesFor(grapeSpellings, country) },
        loadGrapeWineries = { grapeSpellings, country -> grapeWineries(grapeSpellings, country) },
        loadReviews = { grapeSpellings, country -> reviewSample(grapeSpellings, country) },
    )

    private suspend fun reviewSample(spellings: List<String>, country: String) =
        reviews.digestFor(spellings, country, seed = sampleSeed(spellings.first(), country))

    private suspend fun grapeWineries(spellings: List<String>, country: String?) =
        reviews.wineriesFor(spellings, country, seed = sampleSeed(spellings.first(), country.orEmpty()))

    private suspend fun otherCountriesFor(spellings: List<String>, country: String) =
        reviews.topCountriesFor(spellings, excludeCountry = country)

    /** Every spelling of this grape in the review database (Syrah and Shiraz, etc.) counts as the same variety. */
    private fun spellingsOf(variety: String): List<String> = GrapeVarieties.all.firstOrNull { grape ->
        grape.databaseNames.any { it.equals(variety, ignoreCase = true) } || grape.name.equals(variety, ignoreCase = true)
    }?.databaseNames ?: listOf(variety)

    private suspend fun kaggleExtractedFor(
        spellings: List<String>,
        country: String,
        preferredProvince: String,
    ): List<KaggleExtractedProfile> = runCatching { profiles.stylesFor(country, spellings) }.getOrNull().orEmpty()
        .sortedByDescending { it.province.equals(preferredProvince, ignoreCase = true) }
        .take(MAX_REGION_ROWS)
        .map { KaggleExtractedProfile(it.body, it.tannin, it.acidity, it.flavorNotes, it.province, it.country) }

    private fun String.isUnknown() = isBlank() || equals("Unknown", ignoreCase = true)

    /** Same wine, same sample every time: the draw is seeded from the grape and country. */
    private fun sampleSeed(variety: String, country: String): Long =
        ("${variety.lowercase()}|${country.lowercase()}".hashCode().toLong() and 0x7fffffffL)

    private companion object {
        const val MAX_REGION_ROWS = 3
    }
}
