package com.sheldondesousa.uncork.data.reviews

import com.sheldondesousa.uncork.ui.guided.GuidedCriteria

/** OR within each selected field, AND across fields; solely user criteria, never Gemma output. */
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
            if (criteria.wineType.isNotEmpty()) {
                val filter = GuidedWineTypeFilter.forTypes(criteria.wineType)
                if (filter.arguments.isEmpty()) return GuidedReviewQuery("", emptyArray(), knownEmpty = true)
                clauses += filter.sql
                arguments += filter.arguments
            }
            fun evidence(values: Set<String>, phrases: Map<String, List<String>>) {
                if (values.isEmpty()) return
                val matches = values.sorted().flatMap { value ->
                    phrases.getValue(value).also { require(it.isNotEmpty()) { "Missing Find evidence for $value" } }
                }.distinct()
                clauses += matches.joinToString(" OR ", "(", ")") {
                    "review_summary LIKE ? COLLATE NOCASE ESCAPE '\\'"
                }
                arguments += matches.map { phrase ->
                    "%" + phrase.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                }
            }
            fun classified(column: String, values: Set<String>, labels: List<String>) {
                if (values.isEmpty()) return
                require(values.all { it in labels }) { "Unknown Find label for $column" }
                clauses += values.sorted().joinToString(",", "$column IN (", ") COLLATE NOCASE") { "?" }
                arguments += values.sorted()
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
