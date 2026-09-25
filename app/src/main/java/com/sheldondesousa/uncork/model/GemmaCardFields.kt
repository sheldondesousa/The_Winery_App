package com.sheldondesousa.uncork.model

/**
 * Canonical JSON field names accepted by the Gemma card parser.
 *
 * Gemma is asked to use the canonical snake_case names, but smaller models sometimes return a
 * familiar synonym or change the casing. Kotlin resolves those keys before reading the value so
 * the rest of the app only sees its standard field names.
 */
internal object GemmaCardFields {
    private val aliasesByCanonicalName = linkedMapOf(
        "name" to listOf("wine_name", "wineName"),
        "wine_type" to listOf("type", "wineType", "wine type"),
        "country" to listOf("origin_country", "countryOfOrigin"),
        "province" to listOf("region", "wine_region", "wineRegion"),
        "variety" to listOf("grape", "grape_variety", "grapeVariety"),
        "body" to listOf("wine_body", "wineBody", "body_level", "bodyLevel"),
        "tannin" to listOf("tannins", "tannin_level", "tanninLevel"),
        "acidity" to listOf("acid", "acidity_level", "acidityLevel"),
        "sweetness" to listOf("sweetness_level", "sweetnessLevel"),
        "flavor" to listOf(
            "flavour",
            "flavor_profile",
            "flavour_profile",
            "flavorProfile",
            "flavourProfile",
        ),
        // Retained for older responses and other existing parsers, even though the current chat
        // card prompt no longer asks Gemma to generate these fields.
        "winery" to listOf("producer", "wine_producer", "wineProducer"),
        "flavor_notes" to listOf("flavour_notes", "flavorNotes", "flavourNotes"),
        "occasion" to listOf("event", "drinking_occasion", "drinkingOccasion"),
        "suggested_pairing" to listOf(
            "pairing",
            "food_pairing",
            "foodPairing",
            "suggestedPairing",
        ),
        "summary" to listOf("description", "profile_summary", "profileSummary"),
    )

    private val canonicalByNormalizedAlias = buildMap {
        aliasesByCanonicalName.forEach { (canonical, aliases) ->
            put(canonical.normalizedJsonKey(), canonical)
            aliases.forEach { alias -> put(alias.normalizedJsonKey(), canonical) }
        }
    }

    val canonicalNames: Set<String> = aliasesByCanonicalName.keys

    fun canonicalName(key: String): String? = canonicalByNormalizedAlias[key.normalizedJsonKey()]

    private fun String.normalizedJsonKey(): String =
        lowercase().filter(Char::isLetterOrDigit)
}
