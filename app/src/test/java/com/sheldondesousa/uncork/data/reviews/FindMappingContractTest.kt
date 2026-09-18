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
        listOf(GuidedCriteria(), GuidedCriteria(tannin = setOf("High")),
            GuidedCriteria(body = setOf("Full")), GuidedCriteria(wineType = setOf("Missing"))).forEach {
            assertTrue(runCatching { GuidedReviewQuery.from(it) }.isFailure)
        }
    }

    @Test fun typeFilterUsesOnlyExactVarietiesAndUnionsWithoutDuplicates() {
        val filter = GuidedWineTypeFilter.forTypes(setOf("Red", "Sparkling"))
        assertFalse(filter.sql.contains("name"))
        assertFalse(filter.sql.contains("LIKE"))
        assertTrue(filter.arguments.containsAll(listOf("Cabernet Sauvignon", "Shiraz", "Champagne Blend", "Sparkling Blend")))
        assertEquals(filter.arguments.distinct(), filter.arguments)
        assertFalse(GuidedWineTypeFilter.forTypes(setOf("Sparkling")).arguments.contains("Chardonnay"))
        assertTrue(GuidedWineTypeFilter.forTypes(setOf("Fortified")).arguments.contains("Port"))
    }

    @Test fun sweetnessEvidenceRemovesAmbiguousWords() {
        // Body/Tannin/Acidity have no phrase evidence to review anymore: Find matches their
        // precomputed columns directly. Only Sweetness still has review-text phrases to vet.
        val evidence = FindPhraseEvidence.SWEETNESS_EVIDENCE.values.flatten()
        assertTrue(evidence.none { it in setOf("rich", "tart", "dry", "sweet", "mild", "tannic") })
    }

    @Test fun multipleLevelsIncludeEveryOptionWithoutAddingUnselectedFields() {
        val query = GuidedReviewQuery.from(GuidedCriteria(tannin = setOf("Smooth", "Moderate")))
        assertTrue(query.sql.contains("tannin IN"))
        assertTrue(query.arguments.toList().containsAll(listOf("Smooth", "Moderate")))
        assertFalse(query.sql.contains("country="))
        assertFalse(query.sql.contains("variety "))
        assertFalse(query.sql.contains("description"))
        assertEquals(query.sql.count { it == '?' }, query.arguments.size)
    }
}
