package com.sheldondesousa.uncork.data.reviews

/** Match a union of exact variety values; never infer a style from a wine's name. */
internal data class GuidedWineTypeFilter(val sql: String, val arguments: List<String>) {
    companion object {
        fun forTypes(types: Set<String>): GuidedWineTypeFilter {
            require(types.isNotEmpty())
            require(types.all { it in WineTypeVarietyMap.TYPE_TO_VARIETIES }) { "Unmapped Find type" }
            val varieties = types.sorted().flatMap { WineTypeVarietyMap.TYPE_TO_VARIETIES.getValue(it) }.distinct()
            // A deliberately configured but unsupported type has no backing data. Do not broaden.
            if (varieties.isEmpty()) return GuidedWineTypeFilter("0", emptyList())
            return GuidedWineTypeFilter(
                "variety COLLATE NOCASE IN (${varieties.joinToString(",") { "?" }})", varieties,
            )
        }
    }
}
