package com.sheldondesousa.uncork.data.reviews

data class WineReview(
    val id: Long,
    val name: String,
    val winery: String,
    val country: String,
    val province: String,
    val variety: String,
    val points: Int?,
    val reviewSummary: String,
)
