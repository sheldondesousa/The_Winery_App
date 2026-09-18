package com.sheldondesousa.uncork.data.reviews

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sheldondesousa.uncork.ui.guided.GuidedCriteria
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WineReviewRepositoryTest {
    @Test
    fun bundledDatabaseSupportsExactAndKeywordQueries() = runBlocking {
        val repository = WineReviewRepository(ApplicationProvider.getApplicationContext())

        repository.prepare()
        val exact = repository.findExact("Italy", "Piedmont", "Nebbiolo")
        val countryOnly = repository.find(WineReviewCriteria(country = "Italy"))
        val countryAndProvince = repository.find(
            WineReviewCriteria(country = "Italy", province = "Piedmont"),
        )
        val keyword = repository.findByKeywords("mushroom risotto")
        val fullBodiedRed = repository.findByKeywords("I asked for a full-bodied red wine")
        val rankedRedAndBoldPool = repository.findBySelectionPool(
            WineSelectionCriteria(wineType = "red", body = "Full"),
        )
        val rose = repository.findByKeywords("Rosé. No country or attribute preference")

        assertEquals(3, exact.size)
        assertEquals(listOf(99, 99, 99), exact.map(WineReview::points))
        assertEquals("Gaja", exact.first().winery)
        assertTrue(exact.first().reviewSummary.length > 100)
        assertEquals(3, countryOnly.size)
        assertTrue(countryOnly.all { it.country == "Italy" })
        assertEquals(3, countryAndProvince.size)
        assertTrue(countryAndProvince.all { it.country == "Italy" && it.province == "Piedmont" })
        assertEquals(3, keyword.size)
        assertEquals(3, fullBodiedRed.size)
        assertTrue(fullBodiedRed.all { it.points != null })
        assertEquals(
            listOf("Cardinale", "Cayuse", "Charles Smith"),
            rankedRedAndBoldPool.map(WineReview::winery),
        )
        assertEquals(listOf(100, 100, 100), rankedRedAndBoldPool.map(WineReview::points))
        assertEquals(3, rose.size)
        assertTrue(rose.all { review ->
            review.name.contains("rosé", ignoreCase = true) ||
                review.variety.contains("rosé", ignoreCase = true)
        })
    }

    @Test
    fun guidedSearchFallsBackToCountryWhenCuratedProvinceHasNoExactMatch() = runBlocking {
        val repository = WineReviewRepository(ApplicationProvider.getApplicationContext())
        repository.prepare()

        // "Piedmont" is both the curated catalog's province and the Kaggle dataset's literal value.
        val exactMatch = repository.findGuided(GuidedCriteria(country = "Italy", province = "Piedmont"))
        assertFalse(exactMatch.usedProvinceFallback)
        assertTrue(exactMatch.reviews.isNotEmpty())
        assertTrue(exactMatch.reviews.all { it.review.province == "Piedmont" })

        // "Sicily" is a curated province with no literal match (Kaggle buckets it as "Sicily & Sardinia").
        val fallback = repository.findGuided(GuidedCriteria(country = "Italy", province = "Sicily"))
        assertTrue(fallback.usedProvinceFallback)
        assertTrue(fallback.reviews.isNotEmpty())
        assertTrue(fallback.reviews.all { it.review.country == "Italy" })
    }

    @Test
    fun guidedSearchFillsProfileAttributesConfirmedByTheMatchedCriteria() = runBlocking {
        val repository = WineReviewRepository(ApplicationProvider.getApplicationContext())
        repository.prepare()

        val result = repository.findGuided(
            GuidedCriteria(country = "Australia", wineType = setOf("Red"), body = setOf("Full-Bodied")),
        )
        assertTrue(result.reviews.isNotEmpty())
        assertTrue(result.reviews.all { it.wineType == "Red" })
        assertTrue(result.reviews.all { it.body == "Full-Bodied" })
    }
}
