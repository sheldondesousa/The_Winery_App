package com.sheldondesousa.uncork.data.reviews

import com.sheldondesousa.uncork.ui.guided.GuidedCriteria

/**
 * A Kaggle review paired with the taste/type attributes for its profile.
 * Body/Tannin/Acidity are precomputed columns present on every row, so they are always filled in
 * from the row itself, whether or not that field was part of the search criteria. Type and
 * Sweetness are not stored on the row (Type is inferred from variety, Sweetness from review text),
 * so those stay "Unknown" unless the user actually selected and thereby confirmed them.
 */
data class GuidedReviewMatch(
    val review: WineReview,
    val wineType: String = "Unknown",
    val sweetness: String = "Unknown",
    val tannin: String = "Unknown",
    val acidity: String = "Unknown",
    val body: String = "Unknown",
) {
    companion object {
        fun from(review: WineReview, criteria: GuidedCriteria): GuidedReviewMatch = GuidedReviewMatch(
            review = review,
            wineType = matchType(criteria.wineType, review.variety),
            sweetness = matchEvidence(criteria.sweetness, FindPhraseEvidence.SWEETNESS_EVIDENCE, review.reviewSummary),
            tannin = review.tannin,
            acidity = review.acidity,
            body = review.body,
        )

        private fun matchEvidence(
            selected: String,
            evidence: Map<String, List<String>>,
            summary: String,
        ): String {
            if (selected.isBlank()) return "Unknown"
            val matches = evidence.getValue(selected).any { phrase -> summary.contains(phrase, ignoreCase = true) }
            return if (matches) selected else "Unknown"
        }

        private fun matchType(selected: String, variety: String): String {
            if (selected.isBlank()) return "Unknown"
            val matches = WineTypeVarietyMap.TYPE_TO_VARIETIES[selected]?.any { it.equals(variety, ignoreCase = true) } == true
            return if (matches) selected else "Unknown"
        }
    }
}
