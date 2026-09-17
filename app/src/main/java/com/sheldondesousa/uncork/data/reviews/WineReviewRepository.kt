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

        fun evidence(patterns: List<String>) {
            if (patterns.isEmpty()) return
            clauses += patterns.joinToString(prefix = "(", postfix = ")", separator = " OR ") {
                "review_summary COLLATE NOCASE LIKE ? ESCAPE '\\'"
            }
            arguments += patterns.map { "%${it.escapeLikePattern()}%" }
        }

        exact("country", criteria.country)
        exact("province", criteria.province)
        exact("variety", criteria.variety)
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
        criteria.body?.let { evidence(BODY_EVIDENCE[it.lowercase()].orEmpty()) }
        criteria.tannin?.let { evidence(TANNIN_EVIDENCE[it.lowercase()].orEmpty()) }
        criteria.acidity?.let { evidence(ACIDITY_EVIDENCE[it.lowercase()].orEmpty()) }
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
    )

    internal companion object {
        val WINE_TYPE_COLUMNS = listOf("name", "variety")
        val KEYWORD_COLUMNS = listOf(
            "name", "winery", "country", "province", "variety", "review_summary",
        )
        private val RED_VARIETIES = listOf(
            "aglianico", "barbera", "cabernet franc", "cabernet sauvignon", "carignan",
            "carmenere", "corvina", "gamay", "grenache", "malbec", "merlot", "mourvedre",
            "nebbiolo", "nero d'avola", "petite sirah", "pinot noir", "pinotage",
            "sangiovese", "syrah", "tempranillo", "touriga nacional", "zinfandel",
        )
        private val WHITE_VARIETIES = listOf(
            "albariño", "chenin blanc", "chardonnay", "gewürztraminer", "grüner veltliner",
            "marsanne", "moscato", "pinot blanc", "pinot gris", "pinot grigio", "riesling",
            "roussanne", "sauvignon blanc", "semillon", "torrontés", "vermentino",
            "viognier",
        )
        private val BODY_EVIDENCE = mapOf(
            "full" to listOf("bold", "full-bodied", "full bodied", "powerful", "rich", "robust"),
            "medium" to listOf("medium-bodied", "medium bodied", "moderate body"),
            "light" to listOf("light-bodied", "light bodied", "delicate", "lightweight"),
        )
        private val TANNIN_EVIDENCE = mapOf(
            "high" to listOf("high tannin", "firm tannin", "grippy tannin", "powerful tannin"),
            "medium" to listOf("medium tannin", "moderate tannin"),
            "low" to listOf("low tannin", "soft tannin", "silky tannin", "gentle tannin"),
        )
        private val ACIDITY_EVIDENCE = mapOf(
            "high" to listOf("high acidity", "bright acidity", "crisp acidity", "racy acidity"),
            "medium" to listOf("medium acidity", "moderate acidity"),
            "low" to listOf("low acidity", "soft acidity", "mellow acidity"),
        )

        private fun varietiesFor(type: String): List<String> = when (type.lowercase()) {
            "red" -> RED_VARIETIES
            "white" -> WHITE_VARIETIES
            else -> emptyList()
        }

        private fun typePatternsFor(type: String): List<String> = when (type.lowercase()) {
            "rosé", "rose" -> listOf("rosé", "rose")
            "sparkling" -> listOf("sparkling", "champagne", "prosecco", "cava")
            "sweet" -> listOf("dessert", "late harvest", "icewine", "sauternes")
            "fortified" -> listOf("port", "sherry", "madeira", "marsala")
            "red" -> listOf("red blend")
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
