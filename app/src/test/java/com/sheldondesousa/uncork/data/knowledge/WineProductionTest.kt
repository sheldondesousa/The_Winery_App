package com.sheldondesousa.uncork.data.knowledge

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WineProductionTest {
    private val production = WineProduction.fromJson(File("src/main/assets/knowledge/french_wine_production.json").readText())
    private val merlot = production.grape("Merlot")!!
    private val chardonnay = production.grape("Chardonnay")!!

    @Test fun franceListsTheFiveStarterGrapes() {
        assertEquals("France", production.country)
        assertEquals(listOf("Ugni Blanc", "Merlot", "Grenache Noir", "Syrah", "Chardonnay"), production.grapes.map { it.name })
    }

    @Test fun aGrapesPlacesFollowTheSeedTree() {
        assertEquals(
            listOf("France" to 0, "Burgundy" to 1, "Chablis" to 2, "Côte de Beaune" to 2, "Mâconnais" to 2, "Champagne" to 1),
            chardonnay.root.flattened().map { it.first.name to it.second },
        )
    }

    @Test fun countryLevelFactsAreRecordedForThePlaceItself() {
        val facts = production.resolve(merlot, "France").facts
        assertTrue(facts.isNotEmpty())
        assertTrue(facts.none { it.inherited })
    }

    @Test fun aRegionOverridesOnlyWhatItDefinesAndInheritsTheRest() {
        val facts = production.resolve(merlot, "Bordeaux").facts.associateBy { it.key }
        assertFalse(facts.getValue("blending").inherited)
        assertTrue(facts.getValue("blending").value.contains("Cabernet Sauvignon"))
        assertTrue(facts.getValue("harvest").inherited)
        assertEquals("France", facts.getValue("harvest").from)
        assertFalse(facts.getValue("grape_role").inherited)
    }

    @Test fun anAppellationWithNoRecordInheritsFromItsNearestRegion() {
        val facts = production.resolve(chardonnay, "Chablis").facts.associateBy { it.key }
        assertTrue(facts.values.all { it.inherited })
        assertEquals("Burgundy", facts.getValue("malolactic").from)
        assertEquals("France", facts.getValue("harvest").from)
    }

    @Test fun siblingBranchesDoNotLeakIntoEachOther() {
        // Champagne is not under Burgundy, so Burgundy's overrides must not reach it.
        val facts = production.resolve(chardonnay, "Champagne").facts
        assertTrue(facts.all { it.from == "France" })
    }

    @Test fun aGrapeWithNoRecordedFactsShowsNothingRatherThanInventingSome() {
        val syrah = production.grape("Syrah")!!
        assertFalse(syrah.hasFacts)
        assertTrue(production.resolve(syrah, "Northern Rhône").facts.isEmpty())
    }

    @Test fun stepsComeInProductionOrderWithCharacterFirst() {
        val keys = production.resolve(merlot, "Bordeaux").facts.map { it.key }
        assertEquals(listOf("grape_role", "style", "harvest"), keys.take(3))
        assertTrue(keys.indexOf("fermentation") < keys.indexOf("maturation"))
    }

    @Test fun sourcesAndConfidenceComeFromThePlacesAlongThePath() {
        val bordeaux = production.resolve(merlot, "Bordeaux")
        assertTrue(bordeaux.sources.containsAll(listOf("FranceAgriMer", "Conseil Interprofessionnel du Vin de Bordeaux")))
        assertEquals(0.97, bordeaux.confidence!!, 0.0001)
    }

    @Test fun grapesWithFactsAreListedFirst() {
        val names = com.sheldondesousa.uncork.ui.production.grapesInListOrder(production.grapes).map { it.name }
        assertEquals(listOf("Chardonnay", "Merlot", "Grenache Noir", "Syrah", "Ugni Blanc"), names)
    }
}
