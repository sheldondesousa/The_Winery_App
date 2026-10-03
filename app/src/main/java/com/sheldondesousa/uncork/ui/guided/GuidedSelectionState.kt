package com.sheldondesousa.uncork.ui.guided

import com.sheldondesousa.uncork.data.reviews.FindPhraseEvidence
import com.sheldondesousa.uncork.data.reviews.GrapeVarieties
import com.sheldondesousa.uncork.data.reviews.ScoreBand
import com.sheldondesousa.uncork.data.reviews.WineTypeVarietyMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object GuidedOptions {
    val types = WineTypeVarietyMap.TYPE_TO_VARIETIES.keys.toList()
    val sweetness = FindPhraseEvidence.SWEETNESS_EVIDENCE.keys.toList()
    val tannin = FindPhraseEvidence.TANNIN_LABELS
    val acidity = FindPhraseEvidence.ACIDITY_LABELS
    val body = FindPhraseEvidence.BODY_LABELS
    /** Single grapes, most common first. */
    val varieties: List<String> = GrapeVarieties.all.map { it.name }
    fun alphabetically(values: Collection<String>): List<String> {
        val collator = java.text.Collator.getInstance(java.util.Locale.ENGLISH)
        return values.distinct().sortedWith { a, b -> collator.compare(a, b) }
    }
}

/** Every field is single-select: the Find form lets you pick at most one value per question. */
data class GuidedCriteria(
    val country: String = "",
    val province: String = "",
    val wineType: String = "",
    val tannin: String = "",
    val acidity: String = "",
    val body: String = "",
    val sweetness: String = "",
    val variety: String = "",
) {
    val filterCount: Int get() = listOf(wineType, variety, tannin, acidity, body, sweetness).count(String::isNotBlank) +
        (if (country.isNotBlank() || province.isNotBlank()) 1 else 0)
    val valid: Boolean get() = filterCount > 0 &&
        (wineType.isBlank() || wineType in GuidedOptions.types) &&
        (variety.isBlank() || variety in GuidedOptions.varieties) &&
        (tannin.isBlank() || tannin in GuidedOptions.tannin) &&
        (acidity.isBlank() || acidity in GuidedOptions.acidity) &&
        (body.isBlank() || body in GuidedOptions.body) &&
        (sweetness.isBlank() || sweetness in GuidedOptions.sweetness)
    fun withCountry(value: String) = if (value == country) this else copy(country = value, province = "")
    fun constraints(): Map<String, String> = buildMap {
        wineType.takeIf(String::isNotBlank)?.let { put("wine_type", it) }
        variety.takeIf(String::isNotBlank)?.let { put("variety", it) }
        country.takeIf(String::isNotBlank)?.let { put("country", it) }
        province.takeIf(String::isNotBlank)?.let { put("province", it) }
        sweetness.takeIf(String::isNotBlank)?.let { put("sweetness", it) }
        tannin.takeIf(String::isNotBlank)?.let { put("tannin", it) }
        acidity.takeIf(String::isNotBlank)?.let { put("acidity", it) }
        body.takeIf(String::isNotBlank)?.let { put("body", it) }
    }
    val locationLabel: String get() = listOf(
        country.ifBlank { "Any country" },
        province.ifBlank { "Any province" },
    ).joinToString(" · ")
    /** Plain-language search text for the web search, e.g. "Red wine France Bordeaux Dry Full-Bodied". */
    val webQuery: String get() = buildList {
        add(if (wineType.isNotBlank()) "$wineType wine" else "wine")
        if (variety.isNotBlank()) add(variety.replace(" / ", " or "))
        if (country.isNotBlank()) add(country)
        if (province.isNotBlank()) add(province)
        if (sweetness.isNotBlank()) add(sweetness)
        if (body.isNotBlank()) add(body)
        if (tannin.isNotBlank()) add("$tannin tannins")
        if (acidity.isNotBlank()) add("$acidity acidity")
    }.joinToString(" ")
    val description: String get() = buildList {
        if (wineType.isNotBlank()) add(wineType)
        if (variety.isNotBlank()) add("Variety: $variety")
        if (country.isNotBlank() || province.isNotBlank()) add(locationLabel)
        if (sweetness.isNotBlank()) add("Sweetness: $sweetness")
        if (tannin.isNotBlank()) add("Tannin: $tannin")
        if (body.isNotBlank()) add("Body: $body")
        if (acidity.isNotBlank()) add("Acidity: $acidity")
    }.joinToString(" · ")
}

sealed interface GuidedResult {
    data object Idle : GuidedResult
    data class Loading(val cards: List<WineSuggestion> = emptyList()) : GuidedResult
    data class Complete(val cards: List<WineSuggestion>, val usedProvinceFallback: Boolean = false) : GuidedResult
    data object Error : GuidedResult
}

/** One page of reviews for one score tab. [hasMore] says whether a "More" button should be offered. */
data class ReviewsPage(
    val cards: List<WineSuggestion>,
    val hasMore: Boolean,
    val usedProvinceFallback: Boolean = false,
)

/** What one score tab currently shows. */
data class ReviewTab(
    val status: GuidedResult = GuidedResult.Idle,
    val cards: List<WineSuggestion> = emptyList(),
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val usedProvinceFallback: Boolean = false,
)

/** The single sort on the results page. [Ranked] is the default: highest score first. */
enum class GuidedSort(val label: String) {
    Ranked("Top ranked"),
    Country("By Country"),
    Variety("By Variety"),
}

/**
 * Kept above navigation so tab/profile changes do not restart requests or lose results. Find shows
 * only stored data: the reviewed-wines database (two score tabs, ten at a time), then the extended
 * (previously saved web results) db, with a live web search offered on demand. AI Sommelier (Gemma)
 * is Chat-only.
 */
class GuidedSelectionState(
    private val scope: CoroutineScope,
    private val reviewsSearch: suspend (
        criteria: GuidedCriteria,
        band: ScoreBand,
        offset: Int,
        dropProvince: Boolean,
        seed: Long,
    ) -> ReviewsPage,
    private val extendedSearch: suspend (GuidedCriteria) -> GuidedResult.Complete = { GuidedResult.Complete(emptyList()) },
    private val reportDatabaseError: (Exception) -> Unit = {},
    private val locations: Map<String, List<String>> = WineRegions.catalog,
    private val webSearch: suspend (GuidedCriteria) -> List<WineSuggestion> = { emptyList() },
    private val newSeed: () -> Long = { kotlin.random.Random.nextLong(1L, 2_000_000_000L) },
) {
    val countries: List<String> get() = locations.keys.sortedWith { a, b ->
        java.text.Collator.getInstance(java.util.Locale.ENGLISH).compare(a, b)
    }
    val provinces: List<String> get() = GuidedOptions.alphabetically(
        if (selection.country.isBlank()) locations.values.flatten()
        else locations[selection.country].orEmpty(),
    )

    var selection by mutableStateOf(GuidedCriteria())
    var submitted by mutableStateOf<GuidedCriteria?>(null)
        private set
    var showResults by mutableStateOf(false)
        private set
    /** Which score tab is open. 91–100 is the default. */
    var scoreTab by mutableStateOf(ScoreBand.Top)
        private set
    var sort by mutableStateOf(GuidedSort.Ranked)
        private set
    private val reviewTabs = mutableStateMapOf<ScoreBand, ReviewTab>()
    var extended by mutableStateOf<GuidedResult>(GuidedResult.Idle)
        private set
    // Only ever loaded when the user taps "Web Search" on the results page — never automatically.
    var web by mutableStateOf<GuidedResult>(GuidedResult.Idle)
        private set
    private var generation = 0L
    private var seed = 0L
    private val reviewJobs = mutableMapOf<ScoreBand, Job>()
    private var extendedJob: Job? = null
    private var webJob: Job? = null

    fun tab(band: ScoreBand): ReviewTab = reviewTabs[band] ?: ReviewTab()

    /** The loaded reviews for a tab in the chosen sort order. Sorting reorders what is loaded; it never hides results. */
    fun visibleReviews(band: ScoreBand): List<WineSuggestion> {
        val cards = tab(band).cards
        val collator = java.text.Collator.getInstance(java.util.Locale.ENGLISH)
        return when (sort) {
            GuidedSort.Ranked -> cards
            GuidedSort.Country -> cards.sortedWith(compareBy(collator) { it.country })
            GuidedSort.Variety -> cards.sortedWith(compareBy(collator) { it.variety })
        }
    }

    fun selectTab(band: ScoreBand) { scoreTab = band }
    fun selectSort(value: GuidedSort) { sort = value }

    val canSearch: Boolean get() = selection.valid &&
        !(selection == submitted && (ScoreBand.entries.any { tab(it).status is GuidedResult.Loading } ||
            extended is GuidedResult.Loading))

    fun search() {
        if (!canSearch) return
        val criteria = selection
        val request = ++generation
        seed = newSeed()
        reviewJobs.values.forEach { it.cancel() }
        reviewJobs.clear()
        extendedJob?.cancel()
        webJob?.cancel()
        submitted = criteria
        showResults = true
        scoreTab = ScoreBand.Top
        sort = GuidedSort.Ranked
        web = GuidedResult.Idle
        extended = GuidedResult.Loading()
        ScoreBand.entries.forEach { band ->
            reviewTabs[band] = ReviewTab(status = GuidedResult.Loading())
            reviewJobs[band] = scope.launch { loadPage(criteria, band, request, offset = 0, dropProvince = false, first = true) }
        }
        extendedJob = scope.launch {
            val result = try {
                extendedSearch(criteria)
            } catch (cancelled: CancellationException) {
                if (request == generation) extended = GuidedResult.Complete(emptyList())
                throw cancelled
            } catch (error: Exception) {
                reportDatabaseError(error)
                GuidedResult.Complete(emptyList())
            }
            if (request == generation) extended = result
        }
    }

    /** Loads the next ten reviews for a tab. Does nothing if there are no more or a page is already loading. */
    fun loadMore(band: ScoreBand) {
        val criteria = submitted ?: return
        val current = tab(band)
        if (!current.hasMore || current.loadingMore) return
        val request = generation
        reviewTabs[band] = current.copy(loadingMore = true)
        reviewJobs[band] = scope.launch {
            loadPage(criteria, band, request, offset = current.cards.size, dropProvince = current.usedProvinceFallback, first = false)
        }
    }

    private suspend fun loadPage(
        criteria: GuidedCriteria,
        band: ScoreBand,
        request: Long,
        offset: Int,
        dropProvince: Boolean,
        first: Boolean,
    ) {
        val page = try {
            reviewsSearch(criteria, band, offset, dropProvince, seed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportDatabaseError(error)
            ReviewsPage(emptyList(), hasMore = false, usedProvinceFallback = dropProvince)
        }
        if (request != generation) return
        val previous = if (first) emptyList() else tab(band).cards
        reviewTabs[band] = ReviewTab(
            status = GuidedResult.Complete(previous + page.cards, page.usedProvinceFallback),
            cards = previous + page.cards,
            hasMore = page.hasMore,
            loadingMore = false,
            usedProvinceFallback = page.usedProvinceFallback,
        )
    }

    /** Runs the Brave web search for the submitted selections. Safe to tap again after a failure. */
    fun searchWeb() {
        val criteria = submitted ?: return
        if (web is GuidedResult.Loading) return
        val request = generation
        web = GuidedResult.Loading()
        webJob = scope.launch {
            val result = try {
                GuidedResult.Complete(webSearch(criteria))
            } catch (cancelled: CancellationException) {
                if (request == generation) web = GuidedResult.Error
                throw cancelled
            } catch (_: Exception) {
                GuidedResult.Error
            }
            if (request == generation) web = result
        }
    }

    fun backToForm() {
        showResults = false
    }
}
