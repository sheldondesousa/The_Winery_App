package com.sheldondesousa.uncork.data.reviews

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface WineReviewDataSource {
    suspend fun find(criteria: WineReviewCriteria): List<WineReview>

    suspend fun findExact(country: String, province: String, variety: String): List<WineReview> =
        find(WineReviewCriteria(country = country, province = province, variety = variety))

    suspend fun findByKeywords(userQuery: String): List<WineReview>

    suspend fun findBySelectionPool(criteria: WineSelectionCriteria): List<WineReview> = emptyList()
}

data class WineSelectionCriteria(
    val wineType: String? = null,
    val country: String? = null,
    val province: String? = null,
    val variety: String? = null,
    val body: String? = null,
    val tannin: String? = null,
    val acidity: String? = null,
) {
    val hasAnyValue: Boolean
        get() = listOf(wineType, country, province, variety, body, tannin, acidity)
            .any { !it.isNullOrBlank() }
}

data class GuidedDatabaseResult(
    val reviews: List<GuidedReviewMatch>,
    val usedProvinceFallback: Boolean = false,
)

data class WineReviewCriteria(
    val country: String? = null,
    val province: String? = null,
    val variety: String? = null,
) {
    val hasAnyValue: Boolean
        get() = !country.isNullOrBlank() || !province.isNullOrBlank() || !variety.isNullOrBlank()
}

class WineReviewRepository(context: Context) : WineReviewDataSource {
    private val installer = WineReviewDatabaseInstaller(context)

    suspend fun prepare() {
        installer.ensureInstalled()
    }

    /**
     * Falls back to a country-only match when the curated province has no literal match in the
     * Kaggle data (its regions are Wikipedia-derived and rarely line up with the dataset's own
     * province labels). [GuidedDatabaseResult.usedProvinceFallback] tells the UI to say so.
     */
    suspend fun findGuided(
        criteria: com.sheldondesousa.uncork.ui.guided.GuidedCriteria,
    ): GuidedDatabaseResult {
        val guidedQuery = GuidedReviewQuery.from(criteria)
        if (guidedQuery.knownEmpty) return GuidedDatabaseResult(emptyList())
        val exact = query(sql = guidedQuery.sql, arguments = guidedQuery.arguments)
        if (exact.isNotEmpty() || criteria.province.isBlank()) {
            return GuidedDatabaseResult(exact.map { GuidedReviewMatch.from(it, criteria) })
        }
        val fallbackQuery = GuidedReviewQuery.from(criteria, dropProvince = true)
        val fallback = query(sql = fallbackQuery.sql, arguments = fallbackQuery.arguments)
        return GuidedDatabaseResult(
            reviews = fallback.map { GuidedReviewMatch.from(it, criteria) },
            usedProvinceFallback = fallback.isNotEmpty(),
        )
    }

    override suspend fun find(criteria: WineReviewCriteria): List<WineReview> {
        val fields = buildList {
            criteria.country?.takeIf(String::isNotBlank)?.let { add("country" to it) }
            criteria.province?.takeIf(String::isNotBlank)?.let { add("province" to it) }
            criteria.variety?.takeIf(String::isNotBlank)?.let { add("variety" to it) }
        }
        if (fields.isEmpty()) return emptyList()
        val whereClause = fields.joinToString(" AND ") { (column, _) ->
            "$column=? COLLATE NOCASE"
        }
        return query(
            sql = "SELECT * FROM wine_reviews WHERE $whereClause " +
                "ORDER BY points DESC, winery ASC LIMIT 3",
            arguments = fields.map { (_, value) -> value }.toTypedArray(),
        )
    }

    override suspend fun findByKeywords(userQuery: String): List<WineReview> {
        val terms = WineReviewKeywordQuery.terms(userQuery)
        if (terms.isEmpty()) return emptyList()
        val strictResults = queryByKeywordTerms(terms, matchAll = true)
        if (strictResults.isNotEmpty()) return strictResults
        return queryByKeywordTerms(terms, matchAll = false)
    }

    override suspend fun findBySelectionPool(
        criteria: WineSelectionCriteria,
    ): List<WineReview> {
        if (!criteria.hasAnyValue) return emptyList()
        val clauses = mutableListOf<String>()
        val arguments = mutableListOf<String>()

        fun exact(column: String, value: String?) {
            value?.takeIf(String::isNotBlank)?.let {
                clauses += "$column=? COLLATE NOCASE"
                arguments += it
            }
        }

        // body/tannin/acidity are already resolved to the same three-tier column-label values
        // Find uses (e.g. "Medium-Bodied") by the time a Chat answer reaches this point — see
        // ChatFlow.matchTaste() — so this matches the precomputed columns directly, exactly like
        // GuidedReviewQuery does for Find. No free-text evidence search needed for these.
        exact("country", criteria.country)
        exact("province", criteria.province)
        exact("variety", criteria.variety)
        exact("body", criteria.body)
        exact("tannin", criteria.tannin)
        exact("acidity", criteria.acidity)
        criteria.wineType?.let { type ->
            val varieties = varietiesFor(type)
            val typePatterns = typePatternsFor(type)
            val alternatives = buildList {
                if (varieties.isNotEmpty()) {
                    add(
                        varieties.joinToString(
                            prefix = "lower(variety) IN (",
                            postfix = ")",
                        ) { "?" },
                    )
                    arguments += varieties
                }
                typePatterns.forEach { pattern ->
                    add("name COLLATE NOCASE LIKE ? ESCAPE '\\'")
                    arguments += "%${pattern.escapeLikePattern()}%"
                }
            }
            if (alternatives.isNotEmpty()) {
                clauses += alternatives.joinToString(separator = " OR ", prefix = "(", postfix = ")")
            }
        }
        if (clauses.isEmpty()) return emptyList()

        return query(
            sql = "SELECT * FROM wine_reviews WHERE ${clauses.joinToString(" AND ")} " +
                "ORDER BY points DESC, winery ASC LIMIT 3",
            arguments = arguments.toTypedArray(),
        )
    }

    private suspend fun queryByKeywordTerms(
        terms: List<String>,
        matchAll: Boolean,
    ): List<WineReview> {
        val arguments = mutableListOf<String>()
        val termClauses = terms.map { term ->
            val columns = if (term.normalizedForComparison() == "rose") {
                WINE_TYPE_COLUMNS
            } else {
                KEYWORD_COLUMNS
            }
            val patterns = if (term.normalizedForComparison() == "rose") {
                listOf("rose", "rosé")
            } else {
                listOf(term)
            }
            buildList {
                columns.forEach { column ->
                    patterns.forEach { pattern ->
                        add("$column COLLATE NOCASE LIKE ? ESCAPE '\\'")
                        arguments += "%${pattern.escapeLikePattern()}%"
                    }
                }
            }.joinToString(prefix = "(", postfix = ")", separator = " OR ")
        }
        val whereClause = termClauses.joinToString(if (matchAll) " AND " else " OR ")
        return query(
            sql = "SELECT * FROM wine_reviews WHERE $whereClause " +
                "ORDER BY points DESC, winery ASC LIMIT 3",
            arguments = arguments.toTypedArray(),
        )
    }

    private fun String.escapeLikePattern(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun String.normalizedForComparison(): String =
        Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    private suspend fun query(sql: String, arguments: Array<String>): List<WineReview> =
        withContext(Dispatchers.IO) {
            installer.ensureInstalled().openReadOnly().use { database ->
                database.rawQuery(sql, arguments).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.toWineReview())
                    }
                }
            }
        }

    private fun File.openReadOnly(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    private fun Cursor.toWineReview(): WineReview = WineReview(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        winery = getString(getColumnIndexOrThrow("winery")),
        country = getString(getColumnIndexOrThrow("country")),
        province = getString(getColumnIndexOrThrow("province")),
        variety = getString(getColumnIndexOrThrow("variety")),
        points = getColumnIndexOrThrow("points").let { index ->
            if (isNull(index)) null else getInt(index)
        },
        reviewSummary = getString(getColumnIndexOrThrow("review_summary")),
        body = getString(getColumnIndexOrThrow("body")),
        tannin = getString(getColumnIndexOrThrow("tannin")),
        acidity = getString(getColumnIndexOrThrow("acidity")),
    )

    internal companion object {
        val WINE_TYPE_COLUMNS = listOf("name", "variety")
        val KEYWORD_COLUMNS = listOf(
            "name", "winery", "country", "province", "variety", "review_summary",
        )
        // Chat's Q1 wine-type answer ("red"/"rose"/"white"/"sparkling"/"sweet"/"fortified", see
        // ChatFlow.TYPE_KEYWORDS) uses lowercase, unaccented keys; WineTypeVarietyMap (the
        // shared source of truth Find's GuidedWineTypeFilter also uses) keys by the display
        // label. This just bridges the two naming conventions.
        private val CHAT_TYPE_TO_VARIETY_MAP_KEY = mapOf(
            "red" to "Red",
            "white" to "White",
            "sparkling" to "Sparkling",
            "rose" to "Rosé",
            "fortified" to "Fortified",
        )

        /** Exact varieties for a wine type, from the same list Find's exact-match query uses. */
        internal fun varietiesFor(type: String): List<String> =
            CHAT_TYPE_TO_VARIETY_MAP_KEY[type.lowercase()]
                ?.let { WineTypeVarietyMap.TYPE_TO_VARIETIES[it] }
                ?.map { it.lowercase() }
                .orEmpty()

        // "Sweet" is a Chat-only category (Find's WineTypeVarietyMap has no equivalent, since
        // sweetness is a residual-sugar level rather than a distinct variety/style) with no
        // variety list to match exactly, so it's the one type that still infers from the name.
        private fun typePatternsFor(type: String): List<String> = when (type.lowercase()) {
            "sweet" -> listOf("dessert", "late harvest", "icewine", "sauternes")
            else -> emptyList()
        }
    }
}

internal object WineReviewKeywordQuery {
    fun terms(userQuery: String): List<String> =
        WORD.findAll(userQuery.lowercase(Locale.ROOT))
            .map(MatchResult::value)
            .filter { it.length >= MIN_TERM_LENGTH && it !in STOP_WORDS }
            .distinct()
            .take(MAX_TERMS)
            .toList()

    fun from(userQuery: String, matchAll: Boolean = true): String? {
        val terms = terms(userQuery)
        return terms.takeIf(List<String>::isNotEmpty)
            ?.joinToString(if (matchAll) " AND " else " OR ", transform = ::toFtsExpression)
    }

    private fun toFtsExpression(term: String): String =
        if (term.normalizedForComparison() == "rose") {
            "{name variety} : \"$term\""
        } else {
            "\"$term\""
        }

    private fun String.normalizedForComparison(): String =
        Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    private const val MIN_TERM_LENGTH = 3
    private const val MAX_TERMS = 12
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val STOP_WORDS = setOf(
        "and", "are", "ask", "asked", "attribute", "country", "find", "for", "from", "give",
        "have", "into", "looking", "need", "please", "preference", "recommend", "recommendation",
        "suggest", "suggestion", "that", "the", "this", "want", "what", "which", "with", "wine",
        "work", "would", "you", "your",
    )
}
