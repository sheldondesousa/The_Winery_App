package com.sheldondesousa.uncork.data.reviews

import com.sheldondesousa.uncork.model.WinePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for unifying Chat's [WineReviewRepository.findBySelectionPool] with Find's
 * [GuidedReviewQuery]: both should match the precomputed body/tannin/acidity columns directly
 * (no free-text evidence search) and share the same wine-type -> variety mapping
 * ([WineTypeVarietyMap]), so the same effective request no longer produces different results on
 * the two tabs.
 */
class WineReviewRepositoryQueryVocabularyTest {
    @Test
    fun chatBodyTanninAcidityValuesAreAlreadyInTheColumnLabelFormatFindUses() {
        // A "medium body" / "astringent" / "tart" Q3 answer is recorded using the same
        // three-tier labels the precomputed body/tannin/acidity columns hold — the same labels
        // Find's GuidedReviewQuery matches exactly. findBySelectionPool now passes these values
        // straight through as `column=? COLLATE NOCASE` instead of translating them through a
        // separate, mismatched evidence-phrase vocabulary.
        val criteria = WinePreferences(
            body = "Medium-Bodied",
            tannin = "Astringent",
            acidity = "Tart",
        ).toSelectionCriteria()

        assertTrue(FindPhraseEvidence.BODY_LABELS.contains(criteria.body))
        assertTrue(FindPhraseEvidence.TANNIN_LABELS.contains(criteria.tannin))
        assertTrue(FindPhraseEvidence.ACIDITY_LABELS.contains(criteria.acidity))
    }

    @Test
    fun chatAndFindNowShareTheSameRedVarietyList() {
        // Chat's varietiesFor("red") now delegates to WineTypeVarietyMap — the same list Find's
        // GuidedWineTypeFilter uses — instead of maintaining its own separate, drifted list.
        val findRedVarieties = WineTypeVarietyMap.TYPE_TO_VARIETIES.getValue("Red")
            .map { it.lowercase() }.toSet()
        val chatRedVarieties = WineReviewRepository.varietiesFor("red").toSet()

        assertEquals(findRedVarieties, chatRedVarieties)
        assertTrue("red blend" in chatRedVarieties)
        assertTrue("shiraz" in chatRedVarieties)
    }

    @Test
    fun chatAndFindNowShareTheSameWhiteRoseSparklingAndFortifiedVarietyLists() {
        val expectations = mapOf(
            "white" to "White",
            "rose" to "Rosé",
            "sparkling" to "Sparkling",
            "fortified" to "Fortified",
        )
        expectations.forEach { (chatType, findKey) ->
            val findVarieties = WineTypeVarietyMap.TYPE_TO_VARIETIES.getValue(findKey)
                .map { it.lowercase() }.toSet()
            assertEquals(findVarieties, WineReviewRepository.varietiesFor(chatType).toSet())
        }
    }

    @Test
    fun sweetHasNoVarietyMappingSinceFindHasNoEquivalentCategory() {
        // "Sweet" is Chat-only (Q1 offers it; Find's type picker does not), so it correctly has
        // no WineTypeVarietyMap entry to share and stays on the name-based heuristic.
        assertTrue(WineReviewRepository.varietiesFor("sweet").isEmpty())
    }
}
