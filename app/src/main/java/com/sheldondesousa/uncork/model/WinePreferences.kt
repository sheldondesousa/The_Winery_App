package com.sheldondesousa.uncork.model

import org.json.JSONObject

data class WinePreferences(
    val type: String = UNKNOWN,
    val country: String = UNKNOWN,
    val body: String = UNKNOWN,
    val tannin: String = UNKNOWN,
    val acidity: String = UNKNOWN,
    val variety: String = UNKNOWN,
    val flavor: String = UNKNOWN,
    val occasion: String = UNKNOWN,
) {
    fun toCompactJson(): String = JSONObject().apply {
        put("type", type)
        put("country", country)
        put("body", body)
        put("tannin", tannin)
        put("acidity", acidity)
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
    ): Boolean = cardMismatchReasons(
        cardType = cardType,
        cardCountry = cardCountry,
        cardBody = cardBody,
        cardTannin = cardTannin,
        cardAcidity = cardAcidity,
        cardVariety = cardVariety,
        cardFlavor = cardFlavor,
        cardOccasion = cardOccasion,
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
    ): List<String> {
        val normalizedCardType = cardType.normalizedValue()
        return buildList {
            if (normalizedCardType !in WINE_TYPES) add("type is missing or invalid")
            addMismatch("type", type, normalizedCardType)
            addMismatch("country", country, cardCountry)
            addMismatch("body", body, cardBody)
            addMismatch("tannin", tannin, cardTannin)
            addMismatch("acidity", acidity, cardAcidity)
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
                body = json.preference("body"),
                tannin = json.preference("tannin"),
                acidity = json.preference("acidity"),
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
