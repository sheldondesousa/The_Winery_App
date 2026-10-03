package com.sheldondesousa.uncork.data.knowledge

import com.sheldondesousa.uncork.model.RegionStyleFacts
import com.sheldondesousa.uncork.model.WineFactsNote
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GrapeKnowledgeBaseTest {
    private val base = GrapeKnowledgeBase.fromJsonLines(
        File("src/main/assets/knowledge/grape_knowledge.jsonl").readText(),
    )

    @Test fun theBundledSheetLoadsEveryGrape() {
        assertEquals(69, base.size)
    }

    @Test fun aliasesFindTheSameGrapeNoMatterHowTheDatabaseSpellsIt() {
        listOf("Syrah", "Shiraz", "shiraz").forEach {
            assertEquals("Shiraz / Syrah", base.find(it).grapes.single().grape)
        }
        assertEquals("Grenache Noir", base.find("Grenache").grapes.single().grape)
        assertEquals("Mourvèdre", base.find("Monastrell").grapes.single().grape)
        assertEquals("Pinot Noir", base.find("Spätburgunder").grapes.single().grape)
        assertEquals("Mencía", base.find("Jaen").grapes.single().grape)
    }

    @Test fun hyphenBlendsGetNotesForEachNamedGrapeAndAHonestBlendNote() {
        val lookup = base.find("Cabernet Sauvignon-Merlot")
        assertEquals(listOf("Cabernet Sauvignon", "Merlot"), lookup.grapes.map { it.grape })
        assertTrue(lookup.blendNote!!.contains("exact proportions are not known"))
    }

    @Test fun styleLabelsUseTypicalGrapesAndSayTheyAreOnlyTypical() {
        val lookup = base.find("Bordeaux-style Red Blend")
        assertEquals(setOf("Cabernet Sauvignon", "Merlot", "Cabernet Franc"), lookup.grapes.map { it.grape }.toSet())
        assertTrue(lookup.blendNote!!.contains("not known"))
    }

    @Test fun genericBlendsAndUnknownGrapesReturnNoNotesRatherThanGuessing() {
        assertTrue(base.find("Red Blend").grapes.isEmpty())
        assertNotNull(base.find("Red Blend").blendNote)
        assertEquals(GrapeLookup.NONE, base.find("Zzyzx"))
        assertEquals(GrapeLookup.NONE, base.find("Unknown"))
    }

    @Test fun grapesWithHyphensInTheirOwnNameStayOneGrape() {
        assertEquals("Müller-Thurgau", base.find("Müller-Thurgau").grapes.single().grape)
    }

    @Test fun factsNoteLabelsEachTrustLevelAndOmitsUnknownFields() {
        val wine = WineSuggestion(
            name = "Estate Syrah", province = "Rhône Valley", country = "France", variety = "Syrah",
            rating = 91, reviewSummary = "Peppery and dark.", source = WineSuggestionSource.KAGGLE,
        )
        val note = WineFactsNote.build(
            wine, base.find("Syrah"),
            RegionStyleFacts("Full-Bodied", null, "Crisp", listOf("black pepper", "olive")),
        )
        assertTrue(note.startsWith("WINE FACTS"))
        assertTrue(note.contains("reviewed-wines database"))
        assertTrue(note.contains("Critic score: 91"))
        assertTrue(note.contains("GRAPE NOTES (about the grape in general"))
        assertTrue(note.contains("REGION STYLE"))
        assertTrue(note.contains("Typical flavours: black pepper, olive"))
        assertFalse(note.contains("Unknown"))
        assertFalse(note.contains("Typical tannin"))
    }

    @Test fun factsNoteWarnsWhenTheWineIsUnverifiedOrNoGrapeNotesExist() {
        val gemmaWine = WineSuggestion(name = "Made Up", province = "X", source = WineSuggestionSource.GEMMA)
        val note = WineFactsNote.build(gemmaWine)
        assertTrue(note.contains("not verified"))
        assertTrue(note.contains("None available for this variety"))
        assertFalse(note.contains("REGION STYLE"))
    }
}

class ReviewDigestNoteTest {
    private val sampler = com.sheldondesousa.uncork.data.reviews.ReviewSampler

    @Test fun excerptsUseOnlyTheFirstReviewAndCutAtAWordBoundary() {
        val long = "Review 1: " + "plummy ".repeat(60) + "|| Review 2: second review"
        val excerpt = com.sheldondesousa.uncork.data.reviews.ReviewExcerpts.shorten(long, maxChars = 160)
        assertFalse(excerpt.contains("second review"))
        assertFalse(excerpt.startsWith("Review"))
        assertTrue(excerpt.length <= 161)
        assertTrue(excerpt.endsWith("…"))
        assertEquals("Short and sweet.", com.sheldondesousa.uncork.data.reviews.ReviewExcerpts.shorten("Short and sweet."))
    }

    private fun pool(n: Int) = (1..n).map {
        com.sheldondesousa.uncork.data.reviews.PoolReview(it.toLong(), 80 + (it % 20), if (it % 2 == 0) "Bordeaux" else "Languedoc")
    }

    @Test fun samplerDrawsTheSameNumberFromEachThirdOfTheScoreRange() {
        val bands = sampler.sample(pool(300), seed = 7, perBand = 20)
        assertEquals(3, bands.size)
        assertEquals(listOf(20, 20, 20), bands.map { it.picked.size })
        // Every pick really belongs to its own third: the lowest top-third score is at least the highest middle score.
        val tops = bands[0].picked.mapNotNull { it.points }
        val lows = bands[2].picked.mapNotNull { it.points }
        assertTrue(tops.min() >= lows.max())
        assertTrue(bands[0].pool.minOf { it.points!! } >= bands[1].pool.maxOf { it.points!! })
        assertTrue(bands[1].pool.minOf { it.points!! } >= bands[2].pool.maxOf { it.points!! })
    }

    @Test fun samplerIsRepeatableForTheSameSeedAndNotJustTheTopScores() {
        val first = sampler.sample(pool(300), seed = 7).flatMap { it.picked }.map { it.id }
        val again = sampler.sample(pool(300), seed = 7).flatMap { it.picked }.map { it.id }
        val other = sampler.sample(pool(300), seed = 8).flatMap { it.picked }.map { it.id }
        assertEquals(first, again)
        assertNotEquals(first, other)
        val bottom = sampler.sample(pool(300), seed = 7)[2].picked
        assertTrue(bottom.all { it.points!! <= 86 })
    }

    @Test fun samplerHandlesSmallAndUnscoredPools() {
        assertTrue(sampler.sample(emptyList(), 1).isEmpty())
        val unscored = listOf(com.sheldondesousa.uncork.data.reviews.PoolReview(1, null, "X"))
        assertTrue(sampler.sample(unscored, 1).isEmpty())
        val tiny = sampler.sample(pool(5), 1)
        assertEquals(5, tiny.sumOf { it.picked.size })
    }

    @Test fun factsNoteLabelsTheThreeBandsAndMarksTheDataAsAboutTheGrapeNotTheBottle() {
        val digest = com.sheldondesousa.uncork.data.reviews.VarietyCountryDigest(
            variety = "Merlot", country = "France", totalReviews = 120, averagePoints = 88.46,
            bands = listOf(
                com.sheldondesousa.uncork.data.reviews.ReviewBandDigest("highest-scored third", 90, 93, 40,
                    listOf(com.sheldondesousa.uncork.data.reviews.SampledReview("Bordeaux", 92, "Dark plum and cedar."))),
                com.sheldondesousa.uncork.data.reviews.ReviewBandDigest("middle third", 87, 89, 40, emptyList()),
                com.sheldondesousa.uncork.data.reviews.ReviewBandDigest("lowest-scored third", 82, 86, 40,
                    listOf(com.sheldondesousa.uncork.data.reviews.SampledReview("Languedoc-Roussillon", 84, "Thin and simple."))),
            ),
        )
        val note = WineFactsNote.build(WineSuggestion(name = "Chateau X", province = "Bordeaux", country = "France", variety = "Merlot"), reviews = digest)
        assertTrue(note.contains("WHAT WINE ENTHUSIASTS SAY"))
        assertTrue(note.contains("not this bottle"))
        assertTrue(note.contains("<reviews variety=\"Merlot\" country=\"France\" total=\"120\" average_score=\"88.5\">"))
        assertTrue(note.contains("<band name=\"highest-scored third\" points=\"90-93\" sampled=\"1\" of=\"40\">"))
        assertTrue(note.contains("- [Bordeaux, 92] Dark plum and cedar."))
        assertTrue(note.contains("- [Languedoc-Roussillon, 84] Thin and simple."))
        assertFalse(note.contains("middle third"))
        assertTrue(note.contains("</reviews>"))
        assertFalse(WineFactsNote.build(WineSuggestion(name = "A", province = "B")).contains("WHAT WINE ENTHUSIASTS SAY"))
    }
}

class WineReminderTest {
    @Test fun reminderNamesTheWineAndRepeatsTheHonestyRuleInAFewLines() {
        val reminder = WineFactsNote.reminder(
            WineSuggestion(name = "Estate Merlot", province = "Bordeaux", country = "France", variety = "Merlot"),
        )
        assertTrue(reminder.contains("Estate Merlot (Merlot, Bordeaux, France)"))
        assertTrue(reminder.contains("only if they are in WINE FACTS"))
        assertTrue(reminder.length < 400)
    }

    @Test fun reminderSkipsUnknownFields() {
        val reminder = WineFactsNote.reminder(WineSuggestion(name = "Unknown", province = "Unknown"))
        assertTrue(reminder.contains("about this wine."))
        assertFalse(reminder.contains("Unknown"))
    }
}
