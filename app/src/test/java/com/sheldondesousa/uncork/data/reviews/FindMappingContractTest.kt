package com.sheldondesousa.uncork.data.reviews

import com.sheldondesousa.uncork.ui.guided.GuidedCriteria
import com.sheldondesousa.uncork.ui.guided.GuidedOptions
import org.junit.Assert.*
import org.junit.Test

class FindMappingContractTest {
    @Test fun uiOptionsComeFromNonemptyEvidenceMaps() {
        val classifiedGroups = listOf(
            GuidedOptions.body to FindPhraseEvidence.BODY_LABELS,
            GuidedOptions.tannin to FindPhraseEvidence.TANNIN_LABELS,
            GuidedOptions.acidity to FindPhraseEvidence.ACIDITY_LABELS,
        )
        classifiedGroups.forEach { (options, labels) ->
            assertEquals(options.toSet(), labels.toSet())
            assertTrue(labels.all(String::isNotBlank))
        }
        assertEquals(GuidedOptions.sweetness.toSet(), FindPhraseEvidence.SWEETNESS_EVIDENCE.keys)
        assertTrue(FindPhraseEvidence.SWEETNESS_EVIDENCE.values.all { it.isNotEmpty() && it.all(String::isNotBlank) })
        assertEquals(GuidedOptions.types.toSet(), WineTypeVarietyMap.TYPE_TO_VARIETIES.keys)
        assertTrue(WineTypeVarietyMap.TYPE_TO_VARIETIES.values.all { it.isNotEmpty() })
    }

    @Test fun unknownLabelsFailInsteadOfSilentlyDroppingASelectedFilter() {
        listOf(GuidedCriteria(), GuidedCriteria(tannin = "High"),
            GuidedCriteria(body = "Full"), GuidedCriteria(wineType = "Missing")).forEach {
            assertTrue(runCatching { GuidedReviewQuery.from(it) }.isFailure)
        }
    }

    @Test fun typeFilterUsesOnlyExactVarietiesForOneSelectedType() {
        val filter = GuidedWineTypeFilter.forType("Red")
        assertFalse(filter.sql.contains("name"))
        assertFalse(filter.sql.contains("LIKE"))
        assertTrue(filter.arguments.contains("Cabernet Sauvignon"))
        assertEquals(filter.arguments.distinct(), filter.arguments)
        assertFalse(GuidedWineTypeFilter.forType("Sparkling").arguments.contains("Chardonnay"))
        assertTrue(GuidedWineTypeFilter.forType("Fortified").arguments.contains("Port"))
    }

    @Test fun sweetnessEvidenceRemovesAmbiguousWords() {
        // Body/Tannin/Acidity have no phrase evidence to review anymore: Find matches their
        // precomputed columns directly. Only Sweetness still has review-text phrases to vet.
        val evidence = FindPhraseEvidence.SWEETNESS_EVIDENCE.values.flatten()
        assertTrue(evidence.none { it in setOf("rich", "tart", "dry", "sweet", "mild", "tannic") })
    }

    @Test fun oneSelectedLevelMatchesThatColumnWithoutAddingUnselectedFields() {
        val query = GuidedReviewQuery.from(GuidedCriteria(tannin = "Smooth"))
        assertTrue(query.sql.contains("tannin=?"))
        assertTrue(query.arguments.toList().contains("Smooth"))
        assertFalse(query.sql.contains("country="))
        assertFalse(query.sql.contains("variety "))
        assertFalse(query.sql.contains("description"))
        assertEquals(query.sql.count { it == '?' }, query.arguments.size)
    }
}
