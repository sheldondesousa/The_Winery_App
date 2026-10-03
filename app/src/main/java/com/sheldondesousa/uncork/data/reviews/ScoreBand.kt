package com.sheldondesousa.uncork.data.reviews

/** The two score tabs on the Find results page. Every review in the database scores between 80 and 100. */
enum class ScoreBand(val label: String, val min: Int, val max: Int) {
    Top("91–100", 91, 100),
    Standard("80–90", 80, 90),
}
