package com.sheldondesousa.uncork.data.knowledge

import com.sheldondesousa.uncork.model.KaggleExtractedProfile
import com.sheldondesousa.uncork.model.CountryMentions
import com.sheldondesousa.uncork.model.ReviewIntent
import com.sheldondesousa.uncork.model.WineAskExtras
import com.sheldondesousa.uncork.model.WineryIntent
import com.sheldondesousa.uncork.model.WineFactsNote
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GrapeProfileInternalTest {
    private val base = GrapeProfileInternal.fromJsonLines(
        File("src/main/assets/knowledge/grape_profile_internal.jsonl").readText(),
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
        val note = WineFactsNote.build(wine, base.find("Syrah"))
        assertTrue(note.startsWith("WINE FACTS"))
        assertTrue(note.contains("reviewed-wines database"))
        assertTrue(note.contains("Critic score: 91"))
        assertTrue(note.contains("Grape_Profile_Internal (about the grape in general"))
        assertFalse(note.contains("Unknown"))
    }

    private val style = KaggleExtractedProfile("full*", "medium to high", null, listOf("black pepper", "olive"), "Rhône Valley", "France")

    @Test fun regionStyleIsOnlyUsedWhenTheGrapeHasNoNotesAndIsCreditedToEnthusiasts() {
        val wine = WineSuggestion(name = "Estate", province = "Rhône Valley", country = "France", variety = "Zzyzx")
        val withNotes = WineFactsNote.build(wine.copy(variety = "Syrah"), base.find("Syrah"), listOf(style))
        assertFalse(withNotes.contains("Grape_Profile_Kaggle_Extracted"))
        val without = WineFactsNote.build(wine, base.find("Zzyzx"), listOf(style))
        assertTrue(without.contains("Grape_Profile_Kaggle_Extracted: what wine enthusiasts say about Zzyzx in France"))
        assertTrue(without.contains("NOT verified grape facts"))
        assertTrue(without.contains("- Rhône Valley: body full (limited evidence); tannin medium to high; flavours black pepper, olive"))
        assertFalse(without.contains("*"))
        assertFalse(without.contains("acidity"))
    }

    @Test fun factsNoteListsOtherCountriesToOfferAndOmitsThemWhenThereAreNone() {
        val wine = WineSuggestion(name = "Estate", province = "Bordeaux", country = "France", variety = "Merlot")
        val others = listOf(
            com.sheldondesousa.uncork.data.reviews.CountryReviewCount("United States", 900),
            com.sheldondesousa.uncork.data.reviews.CountryReviewCount("Italy", 400),
        )
        val note = WineFactsNote.build(wine, base.find("Merlot"), otherCountries = others)
        assertTrue(note.contains("OTHER COUNTRIES with the most reviews of this grape"))
        assertTrue(note.contains("United States (900 reviews), Italy (400 reviews)"))
        assertFalse(WineFactsNote.build(wine, base.find("Merlot")).contains("OTHER COUNTRIES"))
    }

    @Test fun factsNoteWarnsWhenTheWineIsUnverifiedOrNoGrapeNotesExist() {
        val gemmaWine = WineSuggestion(name = "Made Up", province = "X", source = WineSuggestionSource.GEMMA)
        val note = WineFactsNote.build(gemmaWine)
        assertTrue(note.contains("not verified"))
        assertTrue(note.contains("None available for this variety"))
        assertFalse(note.contains("Grape_Profile_Kaggle_Extracted"))
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

class WineriesDirectoryTest {
    private val directory = WineriesDirectory.fromCsv(
        java.io.File("src/main/assets/knowledge/wineries_directory.csv").readText(),
    )

    @Test fun theBundledDirectoryLoadsEveryWinery() {
        assertTrue(directory.size > 30_000)
    }

    @Test fun matchingIgnoresCaseAccentsAndPunctuation() {
        val hit = directory.find("chateau margaux").single()
        assertEquals("France", hit.country)
        assertEquals("Margaux", hit.region)
        assertEquals(hit, directory.find("CHÂTEAU  MARGAUX!").single())
    }

    @Test fun aKnownCountryOnlyAcceptsWineriesInThatCountry() {
        assertTrue(directory.find("Château Margaux", "France").isNotEmpty())
        assertTrue(directory.find("Château Margaux", "Italy").isEmpty())
        assertTrue(directory.find("Château Margaux", "Unknown").isNotEmpty())
    }

    @Test fun theDirectoryUsesTheSameCountryNamesAsTheReviewDatabase() {
        assertTrue(directory.find("Opus One Winery", "United States").isNotEmpty())
        assertTrue(directory.find("Opus One Winery", "US").isEmpty())
    }

    @Test fun unlistedOrBlankNamesReturnNothingAndQuotedCommasParse() {
        assertTrue(directory.find("Zzyzx Cellars of Nowhere").isEmpty())
        assertTrue(directory.find("  ").isEmpty())
        assertEquals(listOf("A, B", "France", "X"), WineriesDirectory.parseCsvLine("\"A, B\",France,X"))
    }

    @Test fun factsNoteListsTheWineryLocationOnlyWhenFound() {
        val wine = WineSuggestion(name = "Wine", winery = "Château Margaux", province = "Margaux", country = "France")
        val note = WineFactsNote.build(wine, winery = directory.find(wine.winery, wine.country))
        assertTrue(note.contains("Wineries_Directory"))
        assertTrue(note.contains("- Château Margaux: Margaux, France"))
        assertFalse(WineFactsNote.build(wine).contains("Wineries_Directory"))
    }
}

class AskExtrasTest {
    private val base = GrapeProfileInternal.fromJsonLines(
        File("src/main/assets/knowledge/grape_profile_internal.jsonl").readText(),
    )

    private fun digest() = com.sheldondesousa.uncork.data.reviews.VarietyCountryDigest(
        "Merlot", "France", 100, 87.0,
        listOf(
            com.sheldondesousa.uncork.data.reviews.ReviewBandDigest(
                "highest-scored third", 90, 93, 30,
                listOf(com.sheldondesousa.uncork.data.reviews.SampledReview("Bordeaux", 92, "Rich plum.")),
            ),
        ),
    )

    @Test fun reviewIntentSeesQuestionsAboutWhatOtherPeopleThinkAndIgnoresOthers() {
        listOf(
            "What do wine enthusiasts say about it?", "Any reviews?", "what do people think of Merlot from France",
            "what are critics saying", "Is there a consensus?",
        ).forEach { assertTrue(it, ReviewIntent.asksForReviews(it)) }
        listOf("What does it taste like?", "How is it made?", "What score did it get?", "Tell me about Malbec")
            .forEach { assertFalse(it, ReviewIntent.asksForReviews(it)) }
    }

    @Test fun findMentionedSpotsGrapesAndAliasesLongestNameFirstWithoutDoubleCounting() {
        assertEquals(listOf("Malbec"), base.findMentioned("Tell me about malbec please").map { it.grape })
        assertEquals(listOf("Shiraz / Syrah"), base.findMentioned("how is shiraz different?").map { it.grape })
        assertEquals(listOf("Grenache Noir"), base.findMentioned("What is Grenache Noir like").map { it.grape })
        assertEquals(setOf("Pinot Noir", "Merlot"), base.findMentioned("Pinot Noir or Merlot?").map { it.grape }.toSet())
        assertTrue(base.findMentioned("How is wine made and shipped?").isEmpty())
    }

    @Test fun startsWithNothingExtraThenAddsReviewsOnlyWhenAskedAndOnlyOnce() = kotlinx.coroutines.runBlocking {
        var loads = 0
        val extras = WineAskExtras(base, ownGrapes = setOf("Merlot")) { loads++; digest() }
        assertNull(extras.forQuestion("What does it taste like?"))
        assertEquals(0, loads)
        val first = extras.forQuestion("What do wine enthusiasts say?")!!
        assertTrue(first.contains("WHAT WINE ENTHUSIASTS SAY"))
        assertTrue(first.contains("- [Bordeaux, 92] Rich plum."))
        assertNull(extras.forQuestion("Any other reviews?"))
        assertEquals(1, loads)
        extras.reset()
        assertNotNull(extras.forQuestion("Any reviews?"))
        assertEquals(2, loads)
    }

    @Test fun addsNotesForOtherGrapesTheUserNamesButNotTheOpenWinesOwnGrapeAndNeverTwice() = kotlinx.coroutines.runBlocking {
        val extras = WineAskExtras(base, ownGrapes = setOf("Merlot")) { null }
        assertNull(extras.forQuestion("Tell me about Merlot"))
        val malbec = extras.forQuestion("What about Malbec?")!!
        assertTrue(malbec.contains("Grape_Profile_Internal"))
        assertTrue(malbec.contains("- Malbec (red)"))
        assertNull(extras.forQuestion("And Malbec again?"))
        assertNull(extras.forQuestion("What is Zzyzx grape like?"))
    }

    @Test fun saysThereAreNotEnoughReviewsWhenTheSampleIsMissing() = kotlinx.coroutines.runBlocking {
        val extras = WineAskExtras(base, ownGrapes = emptySet()) { null }
        assertTrue(extras.forQuestion("What do people say?")!!.contains("not enough reviews"))
    }
}


class AskExtrasKaggleExtractedTest {
    private val base = GrapeProfileInternal.fromJsonLines(
        File("src/main/assets/knowledge/grape_profile_internal.jsonl").readText(),
    )
    private val style = KaggleExtractedProfile("full", "medium", "medium to high", listOf("plum"), "Piedmont", "Italy")

    @Test fun aGrapeWithoutInternalProfileGetsKaggleExtractedInTheWinesCountryOnceAndCreditedToEnthusiasts() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<Pair<List<String>, String>>()
        val extras = WineAskExtras(
            base, ownGrapes = setOf("Merlot"), loadReviews = { null },
            ownVariety = listOf("Merlot"), ownCountry = "Italy",
            loadKaggleExtracted = { spellings, country -> requests += spellings to country; listOf(style) },
        )
        val block = extras.forQuestion("What about Aglianico?")!!
        assertEquals(listOf(listOf("Aglianico") to "Italy"), requests)
        assertTrue(block.contains("Grape_Profile_Kaggle_Extracted: what wine enthusiasts say about Aglianico in Italy"))
        assertNull(extras.forQuestion("Aglianico again?"))
        assertEquals(1, requests.size)
    }

    @Test fun aGrapeInTheInternalProfileUsesItNotKaggleExtracted() = kotlinx.coroutines.runBlocking {
        var styleLoads = 0
        val extras = WineAskExtras(
            base, ownGrapes = setOf("Merlot"), loadReviews = { null },
            ownVariety = listOf("Merlot"), ownCountry = "France",
            loadKaggleExtracted = { _, _ -> styleLoads++; listOf(style) },
        )
        val block = extras.forQuestion("Tell me about Malbec")!!
        assertTrue(block.contains("Grape_Profile_Internal"))
        assertFalse(block.contains("Grape_Profile_Kaggle_Extracted"))
        assertEquals(0, styleLoads)
    }

    @Test fun namingAnotherCountryGivesTheOpenWinesGrapeStyleThereAndRemovesNothingElse() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<Pair<List<String>, String>>()
        val extras = WineAskExtras(
            base, ownGrapes = setOf("Merlot"), loadReviews = { null },
            ownVariety = listOf("Merlot"), ownCountry = "France",
            loadKaggleExtracted = { spellings, country -> requests += spellings to country; listOf(style.copy(country = country)) },
        )
        val block = extras.forQuestion("Yes, how is it in Italy?")!!
        assertEquals(listOf(listOf("Merlot") to "Italy"), requests)
        assertTrue(block.contains("Grape_Profile_Kaggle_Extracted: what wine enthusiasts say about Merlot in Italy"))
        assertNull(extras.forQuestion("And in Italy again?"))
    }

    @Test fun nothingIsAddedWhenTheAppHasNoStyleForThatGrapeAndCountry() = kotlinx.coroutines.runBlocking {
        val extras = WineAskExtras(
            base, ownGrapes = emptySet(), loadReviews = { null },
            ownVariety = listOf("Merlot"), ownCountry = "France",
        )
        assertNull(extras.forQuestion("What about Aglianico?"))
    }

    @Test fun countryAndGrapeNamesAreRecognisedFromFreeTextIncludingAliases() {
        assertEquals(listOf("Italy"), CountryMentions.find("how about the Italian ones"))
        assertEquals(listOf("United States"), CountryMentions.find("what is it like in the USA?"))
        assertEquals(listOf("South Africa"), CountryMentions.find("South African versions"))
        assertTrue(CountryMentions.find("tell us more").isEmpty())
        assertEquals(listOf("Aglianico"), com.sheldondesousa.uncork.data.reviews.GrapeVarietyLookup.findMentioned("about aglianico?").map { it.name })
        assertTrue(com.sheldondesousa.uncork.data.reviews.GrapeVarietyLookup.findMentioned("does it taste of melon?").isEmpty())
    }
}


class WineriesDirectoryLookupTest {
    private val directory = WineriesDirectory.fromCsv(
        java.io.File("src/main/assets/knowledge/wineries_directory.csv").readText(),
    )
    private val base = GrapeProfileInternal.fromJsonLines(
        java.io.File("src/main/assets/knowledge/grape_profile_internal.jsonl").readText(),
    )

    @Test fun findsWineriesNamedInAQuestionAndIgnoresEverydayWords() {
        val hit = directory.findMentioned("Where is Opus One Winery based?")
        assertEquals("Oakville", hit.first().region)
        assertTrue(directory.findMentioned("which estate has the best winery wines").isEmpty())
        assertTrue(directory.findMentioned("tell me about wine").isEmpty())
    }

    @Test fun findsARegionNamedInAQuestionAndPrefersTheHintedCountry() {
        assertEquals("Argentina" to "Mendoza", directory.regionMentioned("which wineries are in Mendoza?"))
        assertEquals("United States" to "Napa Valley", directory.regionMentioned("wineries in napa valley"))
        assertNull(directory.regionMentioned("what is a good winery"))
    }

    @Test fun samplesWineriesInAPlaceWithoutAlphabeticalBiasOrMoreThanTheLimit() {
        val sample = directory.sampleIn("Argentina", "Mendoza", limit = 5)
        assertEquals(5, sample.size)
        assertTrue(sample.all { it.country == "Argentina" && it.region == "Mendoza" })
        assertNotEquals(sample.map { it.winery }, sample.map { it.winery }.sorted())
        assertEquals(sample, directory.sampleIn("Argentina", "Mendoza", limit = 5))
        assertTrue(directory.countOf("Argentina", "Mendoza") >= 9)
        assertTrue(directory.sampleIn("Argentina").size == 8)
    }

    @Test fun wineryIntentSeesWineryQuestionsOnly() {
        listOf("Which wineries are in Mendoza?", "Where is Opus One located?", "Who makes this?").forEach {
            assertTrue(it, WineryIntent.asksAboutWineries(it))
        }
        listOf("What does Malbec taste like?", "How is wine made?").forEach { assertFalse(it, WineryIntent.asksAboutWineries(it)) }
    }

    @Test fun extrasAnswerWineryQuestionsFromTheDirectoryOnceAndLabelListsAsPartial() = kotlinx.coroutines.runBlocking {
        val extras = WineAskExtras(
            base, ownGrapes = setOf("Merlot"), ownCountry = "France",
            loadWineries = { directory }, loadReviews = { null },
        )
        val named = extras.forQuestion("Where is Opus One Winery located?")!!
        assertTrue(named.contains("Wineries_Directory (entries for wineries named in the question"))
        assertTrue(named.contains("- Opus One Winery: Oakville, United States"))
        assertNull(extras.forQuestion("Where is Opus One Winery located?"))
        val list = extras.forQuestion("Which wineries are in Mendoza?")!!
        assertTrue(list.contains("a small, unranked sample"))
        assertTrue(list.contains("not the best wineries"))
        assertTrue(list.contains(": Mendoza, Argentina"))
        assertNull(extras.forQuestion("Which wineries are in Mendoza?"))
        assertNull(extras.forQuestion("What does it taste like?"))
    }
}
