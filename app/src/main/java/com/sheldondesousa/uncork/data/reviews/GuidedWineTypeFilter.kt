package com.sheldondesousa.uncork.data.reviews

/** Match the exact variety values mapped to one selected type; never infer a style from a wine's name. */
internal data class GuidedWineTypeFilter(val sql: String, val arguments: List<String>) {
    companion object {
        fun forType(type: String): GuidedWineTypeFilter {
            require(type.isNotBlank())
            require(type in WineTypeVarietyMap.TYPE_TO_VARIETIES) { "Unmapped Find type" }
            val varieties = WineTypeVarietyMap.TYPE_TO_VARIETIES.getValue(type).distinct()
            // A deliberately configured but unsupported type has no backing data. Do not broaden.
            if (varieties.isEmpty()) return GuidedWineTypeFilter("0", emptyList())
            return GuidedWineTypeFilter(
                "variety COLLATE NOCASE IN (${varieties.joinToString(",") { "?" }})", varieties,
            )
        }
    }
}
