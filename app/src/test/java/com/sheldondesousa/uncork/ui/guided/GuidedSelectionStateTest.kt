package com.sheldondesousa.uncork.ui.guided

import com.sheldondesousa.uncork.data.reviews.ScoreBand
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class GuidedSelectionStateTest {
    private val criteria = GuidedCriteria("France", "Bordeaux", "Red")
    private val card = WineSuggestion("Merlot", "Bordeaux", country = "France")

    @Test fun anySelectionIsEnoughAndCountryChangesClearProvince() {
        assertFalse(GuidedCriteria().valid)
        assertTrue(GuidedCriteria(country = "France").valid)
        assertTrue(GuidedCriteria(province = "Bordeaux").valid)
        assertTrue(GuidedCriteria(wineType = "Sparkling").valid)
        assertTrue(GuidedCriteria(tannin = "Smooth").valid)
        assertTrue(GuidedCriteria(body = "Full-Bodied").valid)
        assertTrue(GuidedCriteria(acidity = "Crisp").valid)
        assertTrue(criteria.valid)
        assertEquals("", criteria.withCountry("Italy").province)
        assertEquals(criteria, criteria.withCountry("France"))
        assertEquals(setOf("country", "province", "wine_type"), criteria.constraints().keys)
        assertTrue(criteria.copy(body = "Full-Bodied").constraints().containsKey("body"))
        assertFalse(criteria.copy(body = "Impossible").valid)
    }

    @Test fun eachSelectedFieldCountsOnceAndCountIncludesLocationOnce() {
        val criteria = GuidedCriteria(country = "France", province = "Bordeaux", wineType = "Red",
            sweetness = "Bone-Dry", tannin = "Smooth")
        assertTrue(criteria.valid)
        assertEquals(4, criteria.filterCount)
        assertEquals("Red", criteria.constraints()["wine_type"])
        assertEquals("Bone-Dry", criteria.constraints()["sweetness"])
        assertEquals(0, GuidedCriteria().filterCount)
    }

    @Test fun sparseCriteriaExcludeUnselectedFieldsAndMapBodyLevels() {
        assertEquals(mapOf("wine_type" to "Rosé"), GuidedCriteria(wineType = "Rosé").constraints())
        assertEquals(mapOf("body" to "Light-Bodied"), GuidedCriteria(body = "Light-Bodied").constraints())
        assertEquals(mapOf("body" to "Full-Bodied"), GuidedCriteria(body = "Full-Bodied").constraints())
        assertEquals(mapOf("acidity" to "Tart"), GuidedCriteria(acidity = "Tart").constraints())
        assertTrue(GuidedCriteria().constraints().isEmpty())
    }

    private val noReviews: suspend (GuidedCriteria, ScoreBand, Int, Boolean, Long) -> ReviewsPage =
        { _, _, _, _, _ -> ReviewsPage(emptyList(), hasMore = false) }

    @Test fun catalogProvidesAllCountriesAndAlphabeticalCascadingProvinces() = runBlocking {
        val state = GuidedSelectionState(this, noReviews,
            locations = linkedMapOf("US" to listOf("Oregon", "California"),
                "France" to listOf("Burgundy", "Bordeaux"), "Argentina" to listOf("Mendoza")))
        assertEquals(listOf("Argentina", "France", "US"), state.countries)
        assertEquals(listOf("Bordeaux", "Burgundy", "California", "Mendoza", "Oregon"), state.provinces)
        state.selection = GuidedCriteria(country = "France")
        assertEquals(listOf("Bordeaux", "Burgundy"), state.provinces)
    }

    @Test fun defaultCatalogIsTheCuratedWineRegionsList() = runBlocking {
        val state = GuidedSelectionState(this, noReviews)
        assertEquals(WineRegions.catalog.keys.sorted(), state.countries.sorted())
        assertTrue(state.countries.isNotEmpty())
    }

    @Test fun searchLoadsBothScoreTabsAndOpensOnTheTopTabWithTheSameSeedAndCriteria() = runBlocking {
        val calls = mutableListOf<Triple<ScoreBand, Int, Long>>()
        val received = mutableSetOf<Map<String, String>>()
        val state = GuidedSelectionState(this, { c, band, offset, _, seed ->
            received += c.constraints(); calls += Triple(band, offset, seed)
            ReviewsPage(listOf(card.copy(name = "$band wine")), hasMore = false)
        }, newSeed = { 42L })
        state.selection = GuidedCriteria(tannin = "Smooth")
        assertFalse(state.showResults)
        state.search()
        yield()
        assertTrue(state.showResults)
        assertEquals(ScoreBand.Top, state.scoreTab)
        assertEquals(setOf(mapOf("tannin" to "Smooth")), received)
        assertEquals(setOf(ScoreBand.Top to 0, ScoreBand.Standard to 0), calls.map { it.first to it.second }.toSet())
        assertTrue(calls.all { it.third == 42L })
        assertEquals(listOf("Top wine"), state.visibleReviews(ScoreBand.Top).map { it.name })
        assertEquals(listOf("Standard wine"), state.visibleReviews(ScoreBand.Standard).map { it.name })
    }

    @Test fun reviewsShowASkeletonStateWhileLoadingAndASecondSearchWaits() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val state = GuidedSelectionState(this, { _, _, _, _, _ ->
            calls++; release.await(); ReviewsPage(listOf(card), hasMore = false)
        })
        state.selection = criteria
        state.search()
        yield()
        assertTrue(state.tab(ScoreBand.Top).status is GuidedResult.Loading)
        assertFalse(state.canSearch)
        state.search()
        assertEquals(2, calls)
        release.complete(Unit)
        yield()
        assertEquals(listOf(card), state.tab(ScoreBand.Top).cards)
        assertTrue(state.canSearch)
    }

    @Test fun moreLoadsTheNextTenAndKeepsTheFallbackRule() = runBlocking {
        val offsets = mutableListOf<Pair<Int, Boolean>>()
        fun page(from: Int) = List(10) { card.copy(name = "Wine ${from + it}") }
        val state = GuidedSelectionState(this, { _, band, offset, dropProvince, _ ->
            if (band == ScoreBand.Top) offsets += offset to dropProvince
            when {
                band != ScoreBand.Top -> ReviewsPage(emptyList(), false)
                offset == 0 -> ReviewsPage(page(0), hasMore = true, usedProvinceFallback = true)
                else -> ReviewsPage(page(10).take(4), hasMore = false, usedProvinceFallback = true)
            }
        })
        state.selection = criteria
        state.search()
        yield()
        assertTrue(state.tab(ScoreBand.Top).hasMore)
        assertEquals(10, state.tab(ScoreBand.Top).cards.size)
        state.loadMore(ScoreBand.Top)
        yield()
        assertEquals(listOf(0 to false, 10 to true), offsets)
        assertEquals(14, state.tab(ScoreBand.Top).cards.size)
        assertFalse(state.tab(ScoreBand.Top).hasMore)
        state.loadMore(ScoreBand.Top)
        yield()
        assertEquals(2, offsets.size)
    }

    @Test fun sortReordersLoadedReviewsByCountryOrVarietyAndKeepsRankingByDefault() = runBlocking {
        val cards = listOf(
            card.copy(name = "A", country = "Italy", variety = "Barbera"),
            card.copy(name = "B", country = "France", variety = "Merlot"),
            card.copy(name = "C", country = "Chile", variety = "Carmenère"),
        )
        val state = GuidedSelectionState(this, { _, _, _, _, _ -> ReviewsPage(cards, hasMore = false) })
        state.selection = GuidedCriteria(wineType = "Red")
        state.search()
        yield()
        assertEquals(listOf("A", "B", "C"), state.visibleReviews(ScoreBand.Top).map { it.name })
        state.selectSort(GuidedSort.Country)
        assertEquals(listOf("C", "B", "A"), state.visibleReviews(ScoreBand.Top).map { it.name })
        state.selectSort(GuidedSort.Variety)
        assertEquals(listOf("A", "C", "B"), state.visibleReviews(ScoreBand.Top).map { it.name })
        state.search()
        assertEquals(GuidedSort.Ranked, state.sort)
        assertEquals(ScoreBand.Top, state.scoreTab)
    }

    @Test fun databaseFailureLooksEmptyAndDoesNotCancelExtendedDb() = runBlocking {
        var logged = false
        val gate = CompletableDeferred<Unit>()
        val state = GuidedSelectionState(this,
            { _, _, _, _, _ -> gate.await(); error("parse failure") },
            { GuidedResult.Complete(listOf(card)) }, { logged = true })
        state.selection = criteria
        state.search()
        yield()
        assertEquals(GuidedResult.Complete(listOf(card)), state.extended)
        assertTrue(state.tab(ScoreBand.Top).status is GuidedResult.Loading)
        gate.complete(Unit)
        yield()
        assertTrue(logged)
        assertEquals(emptyList<WineSuggestion>(), state.tab(ScoreBand.Top).cards)
        assertFalse(state.tab(ScoreBand.Top).hasMore)
    }

    @Test fun obsoleteResponseCannotReplaceNewResults() = runBlocking {
        val obsolete = CompletableDeferred<Unit>()
        var calls = 0
        val newer = card.copy(name = "New search")
        val state = GuidedSelectionState(this, { _, band, _, _, _ ->
            if (band != ScoreBand.Top) return@GuidedSelectionState ReviewsPage(emptyList(), false)
            calls++
            if (calls == 1) withContext(NonCancellable) { obsolete.await(); ReviewsPage(listOf(card), false) }
            else ReviewsPage(listOf(newer), false)
        })
        state.selection = criteria
        state.search()
        yield()
        state.selection = criteria.copy(body = "Full-Bodied")
        state.search()
        yield()
        assertEquals(listOf(newer), state.tab(ScoreBand.Top).cards)
        obsolete.complete(Unit)
        yield()
        yield()
        assertEquals(listOf(newer), state.tab(ScoreBand.Top).cards)
        assertEquals("Full-Bodied", state.submitted?.body)
    }

    @Test fun webSearchOnlyRunsWhenRequestedAndUsesTheSubmittedSelections() = runBlocking {
        val queries = mutableListOf<String>()
        val webCard = card.copy(name = "Web wine")
        val state = GuidedSelectionState(this, noReviews, webSearch = { queries += it.webQuery; listOf(webCard) })
        state.selection = criteria
        state.search()
        yield()
        assertEquals(GuidedResult.Idle, state.web)
        assertTrue(queries.isEmpty())
        state.searchWeb()
        yield()
        assertEquals(GuidedResult.Complete(listOf(webCard)), state.web)
        assertEquals(listOf("Red wine France Bordeaux"), queries)
    }

    @Test fun webSearchFailureShowsErrorAndANewSearchClearsIt() = runBlocking {
        val state = GuidedSelectionState(this, noReviews, webSearch = { error("offline") })
        state.selection = criteria
        state.search()
        yield()
        state.searchWeb()
        yield()
        assertEquals(GuidedResult.Error, state.web)
        state.search()
        yield()
        assertEquals(GuidedResult.Idle, state.web)
    }
}
