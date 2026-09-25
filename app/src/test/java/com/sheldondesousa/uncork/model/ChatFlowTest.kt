package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.ui.guided.WineRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFlowTest {
    @Test
    fun generatedAliasesNormalizeToCanonicalAppValues() {
        assertEquals("red", canonicalWineType("Red wine"))
        assertEquals("United States", canonicalCountry("US"))
        assertEquals("France", canonicalCountry("French"))
        assertEquals("Bordeaux", canonicalProvince("bordeaux"))
        assertEquals("Full-Bodied", canonicalBody("rich"))
        assertEquals("Smooth", canonicalTannin("silky"))
        assertEquals("Crisp", canonicalAcidity("fresh acid"))
        assertEquals("Off-Dry", canonicalSweetness("semi sweet"))
    }

    @Test
    fun chinaMatchesBecauseItIsInTheRegionCatalog() {
        assertNotNull("China is a WineRegions.catalog key, so Q2 should match it", matchLocation("China"))
        assertEquals("China", matchLocation("China")?.country)
    }

    @Test
    fun indiaNowMatchesAfterBeingAddedToTheRegionCatalog() {
        // Was the root cause of the "India" clarification-loop report: WineRegions.catalog
        // (app/src/main/java/com/sheldondesousa/uncork/ui/guided/WineRegions.kt) had no
        // "India" entry, so matchLocation returned null and — since "India" is a single word
        // with no '?', isLikelyDigression was also false — the deterministic flow just
        // apologized and repeated Q2 instead of accepting the answer. Now fixed.
        assertNotNull(matchLocation("India"))
        assertEquals("India", matchLocation("India")?.country)
    }

    @Test
    fun chineseRedResolvesChinaJustLikeFrenchRedResolvesFrance() {
        // Was a gap: only 12 of the catalog's 43 countries had a demonym alias before this.
        assertEquals(LocationMatch("China", "Unknown"), matchLocation("Chinese Red"))
        assertEquals(LocationMatch("Brazil", "Unknown"), matchLocation("Brazilian white"))
        assertEquals(LocationMatch("South Africa", "Unknown"), matchLocation("a South African red"))
        assertEquals(LocationMatch("United Kingdom", "Unknown"), matchLocation("British sparkling"))
    }

    @Test
    fun isNoPreferenceRecognizesNoneAndNothing() {
        // Was missing from NO_PREFERENCE_PHRASES: these were falling through to matchTaste,
        // matching nothing, and re-asking Q3 instead of accepting the decline.
        assertTrue(isNoPreference("none"))
        assertTrue(isNoPreference("nothing"))
        assertTrue(isNoPreference("nothing in particular"))
    }

    @Test
    fun shortAcronymsResolveTheirCountryWhereUnambiguous() {
        assertEquals(LocationMatch("Australia", "Unknown"), matchLocation("Aus red"))
        assertEquals(LocationMatch("India", "Unknown"), matchLocation("Ind sparkling"))
    }

    @Test
    fun usIsDeliberatelyNotAnAliasBecauseItCollidesWithThePronoun() {
        // "us" the pronoun is common enough in ordinary replies ("suggest one for us") that
        // treating it as a United States acronym would misfire; usa/america/american already
        // cover the unambiguous cases, so "us" alone stays out of COUNTRY_ALIASES.
        assertNull(matchLocation("please suggest one for us"))
        assertEquals(LocationMatch("United States", "Unknown"), matchLocation("USA red"))
    }

    @Test
    fun everyCatalogCountryHasAtLeastOneAlias() {
        val aliasedCountries = COUNTRY_ALIASES.values.toSet()
        val missing = WineRegions.catalog.keys.filterNot { it in aliasedCountries }
        assertTrue("Countries missing a COUNTRY_ALIASES demonym: $missing", missing.isEmpty())
    }

    @Test
    fun frenchRedFiresBothTheTypeAndLocationMatchersOnTheSameReply() {
        // The premise a compound Q1-Q3 answer depends on: two independent matchers can both
        // fire on the same free-text reply without conflicting.
        assertEquals("red", matchWineType("French Red"))
        assertEquals(LocationMatch("France", "Unknown"), matchLocation("French Red"))
    }

    @Test
    fun nextUnresolvedStepWalksTypeThenCountryThenTasteThenNull() {
        assertEquals(FindWineStep.Type, nextUnresolvedStep(WinePreferences()))
        assertEquals(
            FindWineStep.Country,
            nextUnresolvedStep(WinePreferences(type = "red")),
        )
        assertEquals(
            FindWineStep.Taste,
            nextUnresolvedStep(WinePreferences(type = "red", country = "France")),
        )
        assertNull(
            nextUnresolvedStep(
                WinePreferences(type = "red", country = "France", body = "Full-Bodied"),
            ),
        )
    }

    @Test
    fun tasteStepIsResolvedByAnySingleSubField() {
        // Matches finalizePreferences' own behavior: Taste is "answered" once any one of its
        // four sub-fields is set, not only when all four are.
        assertTrue(WinePreferences(tannin = "Smooth").isStepResolved(FindWineStep.Taste))
        assertTrue(WinePreferences(sweetness = "Sweet").isStepResolved(FindWineStep.Taste))
        assertEquals(false, WinePreferences().isStepResolved(FindWineStep.Taste))
    }
}
