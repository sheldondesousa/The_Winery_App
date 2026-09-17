package com.sheldondesousa.uncork.data.profile

import androidx.room.Entity

@Entity(primaryKeys = ["country", "province", "variety"])
data class VarietyRegionProfile(
    val country: String,
    val province: String,
    val variety: String,
    val body: String?,
    val tannin: String?,
    val acidity: String?,
    val flavorNotes: List<String>,
    val generatedBy: String,
    val generatedAt: String,
    val source: String,
    val cachedWineName: String? = null,
    val cachedWinery: String? = null,
    val cachedSuggestedPairing: String? = null,
    val webSummary: String? = null,
)
