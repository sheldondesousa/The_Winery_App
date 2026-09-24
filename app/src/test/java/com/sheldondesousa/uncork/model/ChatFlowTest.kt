package com.sheldondesousa.uncork.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ChatFlowTest {
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
}
