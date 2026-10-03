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

    @Test fun reviewedWinesReturnUpToTenInPointsOrder() {
        val query = GuidedReviewQuery.from(GuidedCriteria(country = "France"))

        assertTrue(query.sql.contains("ORDER BY points IS NULL ASC, points DESC"))
        assertTrue(query.sql.endsWith("LIMIT 10 OFFSET 0"))
    }

    @Test fun varietyListHasEveryGrapeOnceAndIsOrderedMostCommonFirst() {
        val names = GuidedOptions.varieties
        assertEquals(404, names.size)
        assertEquals(names.distinct(), names)
        assertEquals("Pinot Noir", names.first())
        assertTrue(names.indexOf("Chardonnay") < names.indexOf("Nebbiolo"))
        assertTrue(GrapeVarieties.all.all { it.databaseNames.isNotEmpty() })
        assertEquals(listOf("Shiraz", "Syrah"), GrapeVarieties.all.first { it.name == "Syrah / Shiraz" }.databaseNames)
    }

    @Test fun selectedVarietyMatchesEveryMergedDatabaseNameAndCombinesWithOtherFields() {
        val query = GuidedReviewQuery.from(GuidedCriteria(country = "France", variety = "Syrah / Shiraz"))
        assertTrue(query.sql.contains("variety COLLATE NOCASE IN (?,?)"))
        assertTrue(query.arguments.toList().containsAll(listOf("Shiraz", "Syrah", "France")))
        assertEquals(query.sql.count { it == '?' }, query.arguments.size)
        assertTrue(runCatching { GuidedReviewQuery.from(GuidedCriteria(variety = "Not a grape")) }.isFailure)
        assertTrue(GuidedCriteria(variety = "Malbec").valid)
        assertEquals(mapOf("variety" to "Malbec"), GuidedCriteria(variety = "Malbec").constraints())
    }

    @Test fun scoreTabsFilterByPointsAndPageTenAtATime() {
        val top = GuidedReviewQuery.from(GuidedCriteria(country = "France"), band = ScoreBand.Top, offset = 10, limit = 11, seed = 99)
        assertTrue(top.sql.contains("points BETWEEN 91 AND 100"))
        assertTrue(top.sql.endsWith("LIMIT 11 OFFSET 10"))
        val standard = GuidedReviewQuery.from(GuidedCriteria(country = "France"), band = ScoreBand.Standard)
        assertTrue(standard.sql.contains("points BETWEEN 80 AND 90"))
        assertEquals(top.sql.count { it == '?' }, top.arguments.size)
    }

    @Test fun equalScoresAreOrderedByASeededHashNotAlphabeticallyByWinery() {
        val sql = GuidedReviewQuery.from(GuidedCriteria(country = "France"), seed = 12345).sql
        assertFalse(sql.contains("winery ASC"))
        assertTrue(sql.contains("ORDER BY points IS NULL ASC, points DESC, ((id * ${GuidedReviewQuery.hashMultiplier(12345)}) % 2147483647) ASC"))
        assertNotEquals(GuidedReviewQuery.hashMultiplier(1), GuidedReviewQuery.hashMultiplier(2))
    }
}
