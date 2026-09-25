package com.sheldondesousa.uncork.data.reviews

import com.sheldondesousa.uncork.ui.guided.GuidedCriteria

/** Every field is a single selected value, AND across fields; solely user criteria, never Gemma output. */
internal data class GuidedReviewQuery(
    val sql: String,
    val arguments: Array<String>,
    val knownEmpty: Boolean = false,
) {
    companion object {
        fun from(criteria: GuidedCriteria, dropProvince: Boolean = false): GuidedReviewQuery {
            require(criteria.valid) { "Select at least one supported Find option" }
            val clauses = mutableListOf<String>()
            val arguments = mutableListOf<String>()
            fun exact(column: String, value: String) {
                if (value.isBlank()) return
                clauses += "$column=? COLLATE NOCASE"
                arguments += value
            }
            exact("country", criteria.country)
            if (!dropProvince) exact("province", criteria.province)
            if (criteria.wineType.isNotBlank()) {
                val filter = GuidedWineTypeFilter.forType(criteria.wineType)
                if (filter.arguments.isEmpty()) return GuidedReviewQuery("", emptyArray(), knownEmpty = true)
                clauses += filter.sql
                arguments += filter.arguments
            }
            fun evidence(value: String, phrases: Map<String, List<String>>) {
                if (value.isBlank()) return
                val matches = phrases.getValue(value)
                    .also { require(it.isNotEmpty()) { "Missing Find evidence for $value" } }
                clauses += matches.joinToString(" OR ", "(", ")") {
                    "review_summary LIKE ? COLLATE NOCASE ESCAPE '\\'"
                }
                arguments += matches.map { phrase ->
                    "%" + phrase.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                }
            }
            fun classified(column: String, value: String, labels: List<String>) {
                if (value.isBlank()) return
                require(value in labels) { "Unknown Find label for $column" }
                exact(column, value)
            }
            classified("tannin", criteria.tannin, FindPhraseEvidence.TANNIN_LABELS)
            classified("acidity", criteria.acidity, FindPhraseEvidence.ACIDITY_LABELS)
            classified("body", criteria.body, FindPhraseEvidence.BODY_LABELS)
            evidence(criteria.sweetness, FindPhraseEvidence.SWEETNESS_EVIDENCE)
            check(clauses.isNotEmpty())
            return GuidedReviewQuery(
                "SELECT * FROM wine_reviews WHERE ${clauses.joinToString(" AND ")} " +
                    "ORDER BY points IS NULL ASC, points DESC, winery ASC, id ASC LIMIT 3",
                arguments.toTypedArray(),
            )
        }
    }
}
