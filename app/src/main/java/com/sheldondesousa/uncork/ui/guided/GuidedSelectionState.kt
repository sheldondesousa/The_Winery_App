package com.sheldondesousa.uncork.ui.guided

import com.sheldondesousa.uncork.data.reviews.FindPhraseEvidence
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
) {
    val filterCount: Int get() = listOf(wineType, tannin, acidity, body, sweetness).count(String::isNotBlank) +
        (if (country.isNotBlank() || province.isNotBlank()) 1 else 0)
    val valid: Boolean get() = filterCount > 0 &&
        (wineType.isBlank() || wineType in GuidedOptions.types) &&
        (tannin.isBlank() || tannin in GuidedOptions.tannin) &&
        (acidity.isBlank() || acidity in GuidedOptions.acidity) &&
        (body.isBlank() || body in GuidedOptions.body) &&
        (sweetness.isBlank() || sweetness in GuidedOptions.sweetness)
    fun withCountry(value: String) = if (value == country) this else copy(country = value, province = "")
    fun constraints(): Map<String, String> = buildMap {
        wineType.takeIf(String::isNotBlank)?.let { put("wine_type", it) }
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
    val description: String get() = buildList {
        if (wineType.isNotBlank()) add(wineType)
        if (country.isNotBlank() || province.isNotBlank()) add(locationLabel)
        if (sweetness.isNotBlank()) add("Sweetness: $sweetness")
        if (tannin.isNotBlank()) add("Tannin: $tannin")
        if (body.isNotBlank()) add("Body: $body")
        if (acidity.isNotBlank()) add("Acidity: $acidity")
    }.joinToString(" · ")
}

sealed interface GuidedResult {
    data object Idle : GuidedResult
    data object Loading : GuidedResult
    data class Complete(val cards: List<WineSuggestion>, val usedProvinceFallback: Boolean = false) : GuidedResult
    data object Error : GuidedResult
}

/** Kept above navigation so tab/profile changes do not restart requests or lose results. */
class GuidedSelectionState(
    private val scope: CoroutineScope,
    private val gemmaSearch: suspend (GuidedCriteria) -> List<WineSuggestion>,
    private val databaseSearch: suspend (GuidedCriteria) -> GuidedResult.Complete,
    private val reportDatabaseError: (Exception) -> Unit = {},
    private val locations: Map<String, List<String>> = WineRegions.catalog,
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
    var gemma by mutableStateOf<GuidedResult>(GuidedResult.Idle)
        private set
    var database by mutableStateOf<GuidedResult>(GuidedResult.Idle)
        private set
    private var generation = 0L
    private var gemmaJob: Job? = null
    private var databaseJob: Job? = null
    val canSearch: Boolean get() = selection.valid &&
        !(selection == submitted && (gemma == GuidedResult.Loading || database == GuidedResult.Loading))

    fun search() {
        if (!canSearch) return
        val criteria = selection
        val request = ++generation
        gemmaJob?.cancel()
        databaseJob?.cancel()
        submitted = criteria
        showResults = true
        gemma = GuidedResult.Loading
        database = GuidedResult.Loading
        startGemma(criteria, request)
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
    }

    fun backToForm() {
        showResults = false
    }

    fun retryGemma() {
        val criteria = submitted ?: return
        if (gemma != GuidedResult.Error) return
        gemma = GuidedResult.Loading
        startGemma(criteria, generation)
    }

    private fun startGemma(criteria: GuidedCriteria, request: Long) {
        gemmaJob = scope.launch {
            val result = try {
                GuidedResult.Complete(gemmaSearch(criteria))
            } catch (cancelled: CancellationException) {
                if (request == generation) gemma = GuidedResult.Error
                throw cancelled
            } catch (_: Exception) {
                GuidedResult.Error
            }
            if (request == generation) gemma = result
        }
    }
}
