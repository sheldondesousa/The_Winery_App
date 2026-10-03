package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.data.reviews.GrapeVarieties
import com.sheldondesousa.uncork.ui.guided.GuidedCriteria
import com.sheldondesousa.uncork.ui.guided.GuidedOptions

/**
 * Turns the answers collected by the Chat questions into the same selections the Find form produces, so Chat
 * can hand off to the shared Results page. Null when nothing usable was resolved. Chat's "sweet" wine type is
 * a sweetness in Find, and any value Find has no matching option for is left out rather than guessed.
 */
fun WinePreferences.toGuidedCriteria(): GuidedCriteria? {
    fun resolved(value: String) = value.trim().takeIf { it.isNotBlank() && !it.equals(WinePreferences.UNKNOWN, ignoreCase = true) }
    val rawType = resolved(type)?.lowercase()
    val findType = when (rawType) {
        "red" -> "Red"
        "white" -> "White"
        "sparkling" -> "Sparkling"
        "rose", "rosé" -> "Rosé"
        "fortified" -> "Fortified"
        else -> null
    }
    val sweetness = resolved(sweetness)?.takeIf { it in GuidedOptions.sweetness } ?: if (rawType == "sweet") "Sweet" else null
    val grape = resolved(variety)?.let { name ->
        GrapeVarieties.all.firstOrNull { it.name.equals(name, true) || it.databaseNames.any { db -> db.equals(name, true) } }?.name
    }
    return GuidedCriteria(
        country = resolved(country).orEmpty(),
        province = resolved(province).orEmpty(),
        wineType = findType.orEmpty(),
        tannin = resolved(tannin)?.takeIf { it in GuidedOptions.tannin }.orEmpty(),
        acidity = resolved(acidity)?.takeIf { it in GuidedOptions.acidity }.orEmpty(),
        body = resolved(body)?.takeIf { it in GuidedOptions.body }.orEmpty(),
        sweetness = sweetness.orEmpty(),
        variety = grape.orEmpty(),
    ).takeIf { it.valid }
}
