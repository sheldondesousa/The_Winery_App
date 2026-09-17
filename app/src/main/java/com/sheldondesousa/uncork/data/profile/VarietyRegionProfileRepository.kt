package com.sheldondesousa.uncork.data.profile

import com.sheldondesousa.uncork.data.reviews.WineSelectionCriteria
import java.time.Instant

data class CachedWineOption(
    val name: String,
    val winery: String,
    val country: String,
    val province: String,
    val variety: String,
    val body: String?,
    val tannin: String?,
    val acidity: String?,
    val flavorNotes: List<String>,
    val suggestedPairing: String?,
    val webSummary: String?,
)

fun interface WineOptionCache {
    suspend fun find(country: String, province: String, variety: String): CachedWineOption?

    suspend fun save(option: CachedWineOption) = Unit

    suspend fun findMatching(criteria: WineSelectionCriteria): List<CachedWineOption> = emptyList()
}

class VarietyRegionProfileRepository(
    private val dao: VarietyRegionProfileDao,
) : WineOptionCache {
    suspend fun getOrNull(country: String, province: String, variety: String): VarietyRegionProfile? =
        dao.find(country, province, variety)

    override suspend fun find(
        country: String,
        province: String,
        variety: String,
    ): CachedWineOption? {
        val profile = dao.find(country, province, variety) ?: return null
        if (!profile.source.equals(WEB_SEARCH_SOURCE, ignoreCase = true)) return null
        val name = profile.cachedWineName?.takeIf(String::isNotBlank) ?: return null
        return CachedWineOption(
            name = name,
            winery = profile.cachedWinery?.takeIf(String::isNotBlank) ?: "Unknown",
            country = profile.country,
            province = profile.province,
            variety = profile.variety,
            body = profile.body,
            tannin = profile.tannin,
            acidity = profile.acidity,
            flavorNotes = profile.flavorNotes,
            suggestedPairing = profile.cachedSuggestedPairing,
            webSummary = profile.webSummary,
        )
    }

    override suspend fun findMatching(
        criteria: WineSelectionCriteria,
    ): List<CachedWineOption> = dao.findCachedMatches(
        country = criteria.country,
        province = criteria.province,
        variety = criteria.variety,
        body = criteria.body,
        tannin = criteria.tannin,
        acidity = criteria.acidity,
    ).asSequence()
        .filter { profile -> profile.matchesWineType(criteria.wineType) }
        .mapNotNull { profile -> profile.toCachedWineOptionOrNull() }
        .take(3)
        .toList()

    override suspend fun save(option: CachedWineOption) {
        dao.insert(
            VarietyRegionProfile(
                country = option.country,
                province = option.province,
                variety = option.variety,
                body = option.body,
                tannin = option.tannin,
                acidity = option.acidity,
                flavorNotes = option.flavorNotes,
                generatedBy = WEB_SEARCH_SOURCE,
                generatedAt = Instant.now().toString(),
                source = WEB_SEARCH_SOURCE,
                cachedWineName = option.name,
                cachedWinery = option.winery,
                cachedSuggestedPairing = option.suggestedPairing,
                webSummary = option.webSummary,
            ),
        )
    }

    private companion object {
        const val WEB_SEARCH_SOURCE = "web_search"
        val RED_VARIETIES = setOf(
            "aglianico", "barbera", "cabernet franc", "cabernet sauvignon", "carignan",
            "carmenere", "corvina", "gamay", "grenache", "malbec", "merlot", "mourvedre",
            "nebbiolo", "nero d'avola", "petite sirah", "pinot noir", "pinotage",
            "sangiovese", "syrah", "tempranillo", "touriga nacional", "zinfandel",
        )
        val WHITE_VARIETIES = setOf(
            "albariño", "chenin blanc", "chardonnay", "gewürztraminer", "grüner veltliner",
            "marsanne", "moscato", "pinot blanc", "pinot gris", "pinot grigio", "riesling",
            "roussanne", "sauvignon blanc", "semillon", "torrontés", "vermentino",
            "viognier",
        )
    }

    private fun VarietyRegionProfile.matchesWineType(wineType: String?): Boolean = when (
        wineType?.lowercase()
    ) {
        null -> true
        "red" -> variety.lowercase() in RED_VARIETIES ||
            cachedWineName.orEmpty().contains("red", ignoreCase = true)
        "white" -> variety.lowercase() in WHITE_VARIETIES ||
            cachedWineName.orEmpty().contains("white", ignoreCase = true)
        "rosé", "rose" -> listOf(variety, cachedWineName.orEmpty())
            .any { it.contains("rosé", true) || it.contains("rose", true) }
        "sparkling" -> listOf(variety, cachedWineName.orEmpty())
            .any { value ->
                listOf("sparkling", "champagne", "prosecco", "cava")
                    .any { value.contains(it, true) }
            }
        "sweet" -> listOf(variety, cachedWineName.orEmpty())
            .any { value ->
                listOf("dessert", "late harvest", "icewine", "sauternes")
                    .any { value.contains(it, true) }
            }
        "fortified" -> listOf(variety, cachedWineName.orEmpty())
            .any { value ->
                listOf("port", "sherry", "madeira", "marsala")
                    .any { value.contains(it, true) }
            }
        else -> true
    }

    private fun VarietyRegionProfile.toCachedWineOptionOrNull(): CachedWineOption? {
        val name = cachedWineName?.takeIf(String::isNotBlank) ?: return null
        return CachedWineOption(
            name = name,
            winery = cachedWinery?.takeIf(String::isNotBlank) ?: "Unknown",
            country = country,
            province = province,
            variety = variety,
            body = body,
            tannin = tannin,
            acidity = acidity,
            flavorNotes = flavorNotes,
            suggestedPairing = cachedSuggestedPairing,
            webSummary = webSummary,
        )
    }
}
