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

    /**
     * An even-handed sample (equal numbers from the highest, middle and lowest scored thirds) of every review of
     * [varietyNames] (one grape's merged database spellings) from [country]. Null when there is nothing to sample.
     */
    suspend fun digestFor(
        varietyNames: List<String>,
        country: String,
        seed: Long = 0L,
    ): VarietyCountryDigest? = null

    /**
     * A small, unranked sample of wineries that have reviews of a grape (all its spellings), optionally in one country.
     * [totalWineries] is how many such wineries exist. The sample is spread by a seeded hash, not by score or alphabet.
     * Wineries named in [exclude] (lower-case) are skipped, so asking again with the ones already sent gives the next page.
     */
    suspend fun wineriesFor(
        varietyNames: List<String>,
        country: String?,
        limit: Int = 8,
        seed: Long = 0L,
        exclude: Set<String> = emptySet(),
        /** Only wineries whose reviews are for this province (the reviews' own region-level name), when given. */
        province: String? = null,
    ): GrapeWineriesSample? = null

    /**
     * Every winery in the reviews, written the way the reviews write it (with its country and a province), so a winery
     * the user names can be matched to the reviews' own spelling. Empty when the data source cannot list them.
     */
    suspend fun wineryIndex(): List<com.sheldondesousa.uncork.data.knowledge.WineryLocation> = emptyList()

    /**
     * Wines the reviews list for one winery (exact name, optionally in one [country]), as a small unranked sample.
     * Wines named in [exclude] (lower-case) are skipped, so asking again gives the next ones.
     */
    suspend fun winesFor(winery: String, country: String?, limit: Int = 5, exclude: Set<String> = emptySet()): WineryWinesSample? = null

    /** Reviews of the wines whose name contains [wineName], for a grape (any of its spellings); a few at most. */
    suspend fun winesNamed(varietyNames: List<String>, wineName: String, limit: Int = 3): List<WineryWine> = emptyList()

    /** Other countries with the most reviews of this grape, to offer the user. */
    suspend fun topCountriesFor(
        varietyNames: List<String>,
        excludeCountry: String,
        limit: Int = 3,
    ): List<CountryReviewCount> = emptyList()

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

/** A winery that has reviews of a grape in the reviews database, with how many. */
data class GrapeWinery(val winery: String, val country: String, val province: String, val reviewCount: Int)

data class CountryReviewCount(val country: String, val reviewCount: Int)

/** One wine a winery has in the reviews, with the details the reviews hold for it. */
data class WineryWine(
    val name: String,
    val variety: String,
    val province: String,
    val country: String,
    val points: Int?,
    val body: String,
    val tannin: String,
    val acidity: String,
    val review: String,
)

data class WineryWinesSample(val winery: String, val wines: List<WineryWine>, val totalWines: Int)

data class GrapeWineriesSample(val wineries: List<GrapeWinery>, val totalWineries: Int)

data class GuidedReviewPage(
    val reviews: List<GuidedReviewMatch>,
    val hasMore: Boolean,
    val usedProvinceFallback: Boolean = false,
)

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

    /**
     * One page of the Find results for a single score tab. On the first page, falls back to a country-only match
     * when the curated province has no literal match (see [findGuided]); later pages pass [dropProvince] back in
     * so they keep using the same rule.
     */
    suspend fun findGuidedPage(
        criteria: com.sheldondesousa.uncork.ui.guided.GuidedCriteria,
        band: ScoreBand,
        offset: Int,
        seed: Long,
        dropProvince: Boolean = false,
    ): GuidedReviewPage {
        suspend fun load(drop: Boolean): List<WineReview> {
            val q = GuidedReviewQuery.from(
                criteria, dropProvince = drop, band = band, offset = offset,
                limit = GuidedReviewQuery.PAGE_SIZE + 1, seed = seed,
            )
            return if (q.knownEmpty) emptyList() else query(sql = q.sql, arguments = q.arguments)
        }
        var usedFallback = dropProvince
        var rows = load(dropProvince)
        if (rows.isEmpty() && offset == 0 && !dropProvince && criteria.province.isNotBlank()) {
            rows = load(true)
            usedFallback = rows.isNotEmpty()
        }
        return GuidedReviewPage(
            reviews = rows.take(GuidedReviewQuery.PAGE_SIZE).map { GuidedReviewMatch.from(it, criteria) },
            hasMore = rows.size > GuidedReviewQuery.PAGE_SIZE,
            usedProvinceFallback = usedFallback,
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

    override suspend fun digestFor(
        varietyNames: List<String>,
        country: String,
        seed: Long,
    ): VarietyCountryDigest? {
        val names = varietyNames.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty() || country.isBlank()) return null
        val base = "country=? COLLATE NOCASE AND variety COLLATE NOCASE IN (${names.joinToString(",") { "?" }}) " +
            "AND province IS NOT NULL AND province<>'' AND province NOT LIKE '% Other' AND points IS NOT NULL"
        val baseArgs = arrayOf(country, *names.toTypedArray())
        return withContext(Dispatchers.IO) {
            installer.ensureInstalled().openReadOnly().use { database ->
                val pool = database.rawQuery("SELECT id, points, province FROM wine_reviews WHERE $base", baseArgs).use { c ->
                    buildList { while (c.moveToNext()) add(PoolReview(c.getLong(0), c.getInt(1), c.getString(2))) }
                }
                val bands = ReviewSampler.sample(pool, seed)
                val pickedIds = bands.flatMap { it.picked }.map { it.id }
                if (pickedIds.isEmpty()) return@use null
                val text = database.rawQuery(
                    "SELECT id, review_summary FROM wine_reviews WHERE id IN (${pickedIds.joinToString(",")})", null,
                ).use { c ->
                    buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1).orEmpty()) }
                }
                VarietyCountryDigest(
                    variety = names.first(),
                    country = country,
                    totalReviews = pool.size,
                    averagePoints = pool.mapNotNull { it.points }.average().takeIf { !it.isNaN() },
                    bands = bands.map { band ->
                        ReviewBandDigest(
                            name = band.name,
                            minPoints = band.pool.mapNotNull { it.points }.minOrNull(),
                            maxPoints = band.pool.mapNotNull { it.points }.maxOrNull(),
                            poolSize = band.pool.size,
                            reviews = band.picked.mapNotNull { pick ->
                                ReviewExcerpts.shorten(text[pick.id].orEmpty(), maxChars = 140)
                                    .takeIf { it.isNotBlank() }
                                    ?.let { SampledReview(pick.province, pick.points, it) }
                            },
                        )
                    },
                )
            }
        }
    }

    override suspend fun wineriesFor(
        varietyNames: List<String>,
        country: String?,
        limit: Int,
        seed: Long,
        exclude: Set<String>,
        province: String?,
    ): GrapeWineriesSample? {
        val names = varietyNames.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return null
        val countryClause = (if (country.isNullOrBlank()) "" else " AND country=? COLLATE NOCASE") +
            (if (province.isNullOrBlank()) "" else " AND province=? COLLATE NOCASE")
        val args = arrayOf(
            *names.toTypedArray(), *(if (country.isNullOrBlank()) emptyArray() else arrayOf(country)),
            *(if (province.isNullOrBlank()) emptyArray() else arrayOf(province)),
        )
        val all = withContext(Dispatchers.IO) {
            installer.ensureInstalled().openReadOnly().use { database ->
                database.rawQuery(
                    "SELECT winery, country, MIN(province), COUNT(*) FROM wine_reviews " +
                        "WHERE variety COLLATE NOCASE IN (${names.joinToString(",") { "?" }}) AND winery<>''$countryClause " +
                        "GROUP BY winery, country",
                    args,
                ).use { c -> buildList { while (c.moveToNext()) add(GrapeWinery(c.getString(0), c.getString(1), c.getString(2).orEmpty(), c.getInt(3))) } }
            }
        }
        if (all.isEmpty()) return null
        val multiplier = GuidedReviewQuery.hashMultiplier(seed)
        val spread = all.filter { it.winery.lowercase() !in exclude }
            .sortedBy { (it.winery.lowercase().hashCode().toLong() and 0x7fffffffL) * multiplier % 2147483647L }
        return GrapeWineriesSample(spread.take(limit.coerceIn(1, 10)), all.size)
    }

    override suspend fun wineryIndex(): List<com.sheldondesousa.uncork.data.knowledge.WineryLocation> = withContext(Dispatchers.IO) {
        installer.ensureInstalled().openReadOnly().use { database ->
            database.rawQuery("SELECT winery, country, MIN(province) FROM wine_reviews WHERE winery<>'' GROUP BY winery, country", null).use { c ->
                buildList {
                    while (c.moveToNext()) add(com.sheldondesousa.uncork.data.knowledge.WineryLocation(c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty()))
                }
            }
        }
    }

    override suspend fun winesFor(winery: String, country: String?, limit: Int, exclude: Set<String>): WineryWinesSample? {
        if (winery.isBlank()) return null
        val countryClause = if (country.isNullOrBlank()) "" else " AND country=? COLLATE NOCASE"
        val args = arrayOf(winery, *(if (country.isNullOrBlank()) emptyArray() else arrayOf(country)))
        val all = withContext(Dispatchers.IO) {
            installer.ensureInstalled().openReadOnly().use { database ->
                database.rawQuery(
                    "SELECT name, variety, province, country, points, body, tannin, acidity, review_summary FROM wine_reviews " +
                        "WHERE winery=? COLLATE NOCASE$countryClause ORDER BY id",
                    args,
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            WineryWine(
                                c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(),
                                if (c.isNull(4)) null else c.getInt(4),
                                c.getString(5).orEmpty(), c.getString(6).orEmpty(), c.getString(7).orEmpty(), c.getString(8).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }.distinctBy { it.name.lowercase() }
        if (all.isEmpty()) return null
        // Spread by a fixed hash of the name rather than by score, so this is never "the best" wines.
        val spread = all.filter { it.name.lowercase() !in exclude }
            .sortedBy { (it.name.lowercase().hashCode().toLong() and 0x7fffffffL) * 2654435761L % 2147483647L }
        return WineryWinesSample(winery, spread.take(limit.coerceIn(1, 10)), all.size)
    }

    override suspend fun winesNamed(varietyNames: List<String>, wineName: String, limit: Int): List<WineryWine> {
        val names = varietyNames.filter { it.isNotBlank() }.distinct()
        // Every word of the name must appear in the wine's name, in any order.
        val words = wineName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (names.isEmpty() || words.isEmpty() || wineName.trim().length < 3) return emptyList()
        return withContext(Dispatchers.IO) {
            installer.ensureInstalled().openReadOnly().use { database ->
                database.rawQuery(
                    "SELECT name, variety, province, country, points, body, tannin, acidity, review_summary FROM wine_reviews " +
                        "WHERE variety COLLATE NOCASE IN (${names.joinToString(",") { "?" }}) ${words.joinToString("") { " AND name LIKE ? ESCAPE '\\'" }} " +
                        "ORDER BY points DESC, id LIMIT ${limit.coerceIn(1, 5)}",
                    arrayOf(*names.toTypedArray(), *words.map { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }.toTypedArray()),
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            WineryWine(
                                c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(),
                                if (c.isNull(4)) null else c.getInt(4),
                                c.getString(5).orEmpty(), c.getString(6).orEmpty(), c.getString(7).orEmpty(), c.getString(8).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }
    }

    override suspend fun topCountriesFor(
        varietyNames: List<String>,
        excludeCountry: String,
        limit: Int,
    ): List<CountryReviewCount> {
        val names = varietyNames.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            installer.ensureInstalled().openReadOnly().use { database ->
                database.rawQuery(
                    "SELECT country, COUNT(*) FROM wine_reviews WHERE variety COLLATE NOCASE IN (${names.joinToString(",") { "?" }}) " +
                        "AND country<>'' AND country<>? COLLATE NOCASE GROUP BY country ORDER BY COUNT(*) DESC, country ASC " +
                        "LIMIT ${limit.coerceIn(1, 6)}",
                    arrayOf(*names.toTypedArray(), excludeCountry),
                ).use { c -> buildList { while (c.moveToNext()) add(CountryReviewCount(c.getString(0), c.getInt(1))) } }
            }
        }
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
