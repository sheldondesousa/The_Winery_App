package com.sheldondesousa.uncork.data.profile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedCountryNamesTest {
    private val seed = File("src/main/assets/grape_profile_kaggle_extracted.json").readText()
    private val countries = Regex("\"country\": \"([^\"]+)\"").findAll(seed).map { it.groupValues[1] }.toSet()

    @Test fun usesTheSameCountryNamesAsTheReviewDatabaseAndFind() {
        assertTrue("United States" in countries)
        assertTrue("United Kingdom" in countries)
        assertTrue("US" !in countries)
        assertTrue("England" !in countries)
        assertEquals(43, countries.size)
    }
}
