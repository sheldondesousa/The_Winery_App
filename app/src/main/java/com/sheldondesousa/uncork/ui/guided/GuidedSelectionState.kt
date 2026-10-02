package com.sheldondesousa.uncork.ui.guided

import com.sheldondesousa.uncork.data.reviews.FindPhraseEvidence
import com.sheldondesousa.uncork.data.reviews.GrapeVarieties
import com.sheldondesousa.uncork.data.reviews.WineTypeVarietyMap
import androidx.compose.runtime.getValue
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

/**
 * Kept above navigation so tab/profile changes do not restart requests or lose results. Find shows
 * only stored data: the reviewed-wines database, then the extended (previously saved web results)
 * db, with a live web search offered on demand. AI Sommelier (Gemma) is Chat-only.
 */
class GuidedSelectionState(
    private val scope: CoroutineScope,
    private val databaseSearch: suspend (GuidedCriteria) -> GuidedResult.Complete,
    private val extendedSearch: suspend (GuidedCriteria) -> GuidedResult.Complete = { GuidedResult.Complete(emptyList()) },
    private val reportDatabaseError: (Exception) -> Unit = {},
    private val locations: Map<String, List<String>> = WineRegions.catalog,
    private val webSearch: suspend (GuidedCriteria) -> List<WineSuggestion> = { emptyList() },
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
    var database by mutableStateOf<GuidedResult>(GuidedResult.Idle)
        private set
    var extended by mutableStateOf<GuidedResult>(GuidedResult.Idle)
        private set
    // Only ever loaded when the user taps "Web Search" on the results page — never automatically.
    var web by mutableStateOf<GuidedResult>(GuidedResult.Idle)
        private set
    private var generation = 0L
    private var databaseJob: Job? = null
    private var extendedJob: Job? = null
    private var webJob: Job? = null
    val canSearch: Boolean get() = selection.valid &&
        !(selection == submitted && (database is GuidedResult.Loading || extended is GuidedResult.Loading))

    fun search() {
        if (!canSearch) return
        val criteria = selection
        val request = ++generation
        databaseJob?.cancel()
        extendedJob?.cancel()
        webJob?.cancel()
        submitted = criteria
        showResults = true
        database = GuidedResult.Loading()
        extended = GuidedResult.Loading()
        web = GuidedResult.Idle
        databaseJob = scope.launch {
            val result = try {
                databaseSearch(criteria)
            } catch (cancelled: CancellationException) {
                if (request == generation) database = GuidedResult.Complete(emptyList())
                throw cancelled
            } catch (error: Exception) {
                reportDatabaseError(error)
                GuidedResult.Complete(emptyList())
            }
            if (request == generation) database = result
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
