package com.sheldondesousa.uncork.data.reviews

/**
 * UI labels and query keys share these definitions. Audit and exclusions: Docs/Find-Database-Mapping.md.
 *
 * Body/Tannin/Acidity: Find matches the precomputed `body`/`tannin`/`acidity` columns directly
 * ([GuidedReviewQuery]), so these are plain label lists, not phrase evidence — there is no review
 * text to search. [FindPhraseEvidence] remains their single source for the UI option lists and
 * query validation.
 * Sweetness has no precomputed column, so it still matches `review_summary` phrases directly.
 */
object FindPhraseEvidence {
    val BODY_LABELS: List<String> = listOf("Light-Bodied", "Medium-Bodied", "Full-Bodied")
    val TANNIN_LABELS: List<String> = listOf("Smooth", "Moderate", "Astringent")
    val ACIDITY_LABELS: List<String> = listOf("Soft", "Crisp", "Tart")
    val SWEETNESS_EVIDENCE: Map<String, List<String>> = linkedMapOf(
        "Bone-Dry" to listOf("bone-dry", "bone dry", "crisp and dry"),
        "Off-Dry" to listOf("off-dry", "off dry", "semi-sweet", "hint of sweetness", "touch of residual sugar"),
        "Sweet" to listOf("dessert wine", "lusciously sweet", "honeyed sweetness"),
    )
}
