package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.data.knowledge.GrapeKnowledgeBase
import com.sheldondesousa.uncork.data.profile.VarietyRegionProfileRepository
import com.sheldondesousa.uncork.data.reviews.GrapeVarieties
import com.sheldondesousa.uncork.data.reviews.WineReviewDataSource
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion

/** Gathers the three knowledge layers for one wine and turns them into the facts note Gemma is given. */
class WineAskContext(
    private val knowledge: GrapeKnowledgeBase,
    private val profiles: VarietyRegionProfileRepository,
    private val reviews: WineReviewDataSource,
) {
    suspend fun factsFor(wine: WineSuggestion): String {
        val region = runCatching { profiles.getOrNull(wine.country, wine.province, wine.variety) }
            .getOrNull()
            ?.let { RegionStyleFacts(it.body, it.tannin, it.acidity, it.flavorNotes) }
        // Every spelling of this grape in the review database (Syrah and Shiraz, etc.) counts as the same variety.
        val spellings = GrapeVarieties.all.firstOrNull { grape ->
            grape.databaseNames.any { it.equals(wine.variety, ignoreCase = true) } ||
                grape.name.equals(wine.variety, ignoreCase = true)
        }?.databaseNames ?: listOf(wine.variety)
        val digest = if (wine.country.isUnknown() || wine.variety.isUnknown()) null else {
            runCatching { reviews.digestFor(spellings, wine.country, seed = sampleSeed(spellings.first(), wine.country)) }.getOrNull()
        }
        return WineFactsNote.build(wine, knowledge.find(wine.variety), region, digest)
    }

    private fun String.isUnknown() = isBlank() || equals("Unknown", ignoreCase = true)

    /** Same wine, same sample every time: the draw is seeded from the grape and country. */
    private fun sampleSeed(variety: String, country: String): Long =
        ("${variety.lowercase()}|${country.lowercase()}".hashCode().toLong() and 0x7fffffffL)
}
