package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.data.reviews.WineSelectionCriteria
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import org.json.JSONObject

data class WinePreferences(
    val type: String = UNKNOWN,
    val country: String = UNKNOWN,
    val province: String = UNKNOWN,
    val body: String = UNKNOWN,
    val tannin: String = UNKNOWN,
    val acidity: String = UNKNOWN,
    val sweetness: String = UNKNOWN,
    val variety: String = UNKNOWN,
    val flavor: String = UNKNOWN,
    val occasion: String = UNKNOWN,
) {
    /**
     * Direct field mapping to the structured Kaggle/cache search shape — no free-text parsing,
     * since these values were already resolved deterministically during the Q1-Q3 chat flow.
     */
    fun toSelectionCriteria(): WineSelectionCriteria = WineSelectionCriteria(
        wineType = type.takeIfResolved(),
        country = country.takeIfResolved(),
        province = province.takeIfResolved(),
        variety = variety.takeIfResolved(),
        body = body.takeIfResolved(),
        tannin = tannin.takeIfResolved(),
        acidity = acidity.takeIfResolved(),
    )

    private fun String.takeIfResolved(): String? =
        takeIf { it.isNotBlank() && !it.equals(UNKNOWN, ignoreCase = true) }

    /**
     * A [WineSuggestion] "basis" built purely from these recorded answers, for enriching a
     * Kaggle-sourced card without waiting on Gemma's synthesized one. Carries only what these
     * deterministic Q1-Q3 answers actually resolved (type/body/tannin/acidity/sweetness/etc.) —
     * free-text fields Gemma alone can produce (flavor notes, suggested pairing, summary) are
     * left at their "Unknown" default.
     */
    fun toBasisSuggestion(): WineSuggestion = WineSuggestion(
        name = UNKNOWN,
        province = province,
        country = country,
        wineType = type,
        variety = variety,
        sweetness = sweetness,
        body = body,
        tannin = tannin,
        acidity = acidity,
        preferenceFlavor = flavor,
        occasion = occasion,
    )

    fun toCompactJson(): String = JSONObject().apply {
        put("type", type)
        put("country", country)
        put("province", province)
        put("body", body)
        put("tannin", tannin)
        put("acidity", acidity)
        put("sweetness", sweetness)
        put("variety", variety)
        put("flavor", flavor)
        put("occasion", occasion)
    }.toString()

    fun acceptsCard(
        cardType: String,
        cardCountry: String,
        cardBody: String,
        cardTannin: String,
        cardAcidity: String,
        cardVariety: String,
        cardFlavor: String,
        cardOccasion: String,
        cardProvince: String = UNKNOWN,
        cardSweetness: String = UNKNOWN,
    ): Boolean = cardMismatchReasons(
        cardType = cardType,
        cardCountry = cardCountry,
        cardBody = cardBody,
        cardTannin = cardTannin,
        cardAcidity = cardAcidity,
        cardVariety = cardVariety,
        cardFlavor = cardFlavor,
        cardOccasion = cardOccasion,
        cardProvince = cardProvince,
        cardSweetness = cardSweetness,
    ).isEmpty()

    fun cardMismatchReasons(
        cardType: String,
        cardCountry: String,
        cardBody: String,
        cardTannin: String,
        cardAcidity: String,
        cardVariety: String,
        cardFlavor: String,
        cardOccasion: String,
        cardProvince: String = UNKNOWN,
        cardSweetness: String = UNKNOWN,
    ): List<String> {
        val normalizedCardType = cardType.normalizedValue()
        return buildList {
            if (normalizedCardType !in WINE_TYPES) add("type is missing or invalid")
            addMismatch("type", type, normalizedCardType)
            addMismatch("country", country, cardCountry)
            addMismatch("province", province, cardProvince)
            addMismatch("body", body, cardBody)
            addMismatch("tannin", tannin, cardTannin)
            addMismatch("acidity", acidity, cardAcidity)
            addMismatch("sweetness", sweetness, cardSweetness)
            addMismatch("variety", variety, cardVariety)
            addMismatch("flavor", flavor, cardFlavor)
            addMismatch("occasion", occasion, cardOccasion)
        }.distinct()
    }

    companion object {
        const val UNKNOWN = "Unknown"
        private val WINE_TYPES = setOf("red", "rose", "white", "sparkling", "sweet", "fortified")

        fun fromJsonOrNull(value: String): WinePreferences? = runCatching {
            val json = JSONObject(value)
            WinePreferences(
                type = json.preference("type"),
                country = json.preference("country"),
                province = json.preference("province"),
                body = json.preference("body"),
                tannin = json.preference("tannin"),
                acidity = json.preference("acidity"),
                sweetness = json.preference("sweetness"),
                variety = json.preference("variety"),
                flavor = json.preference("flavor"),
                occasion = json.preference("occasion"),
            )
        }.getOrNull()

        private fun JSONObject.preference(key: String): String =
            optString(key).trim().takeIf { it.isNotBlank() && !it.equals("null", true) } ?: UNKNOWN

        private fun MutableList<String>.addMismatch(
            field: String,
            preference: String,
            cardValue: String,
        ) {
            val normalizedPreference = preference.normalizedValue()
            if (
                normalizedPreference != UNKNOWN.lowercase() &&
                cardValue.normalizedValue() != normalizedPreference
            ) {
                add("$field expected '$preference' but was '$cardValue'")
            }
        }

        private fun String.normalizedValue(): String = lowercase()
            .replace("rosé", "rose")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
