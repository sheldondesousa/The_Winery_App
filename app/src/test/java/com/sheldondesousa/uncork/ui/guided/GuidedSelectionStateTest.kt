package com.sheldondesousa.uncork.ui.guided

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

    private val none: suspend (GuidedCriteria) -> GuidedResult.Complete = { GuidedResult.Complete(emptyList()) }

    @Test fun catalogProvidesAllCountriesAndAlphabeticalCascadingProvinces() = runBlocking {
        val state = GuidedSelectionState(this, none,
            locations = linkedMapOf("US" to listOf("Oregon", "California"),
                "France" to listOf("Burgundy", "Bordeaux"), "Argentina" to listOf("Mendoza")))
        assertEquals(listOf("Argentina", "France", "US"), state.countries)
        assertEquals(listOf("Bordeaux", "Burgundy", "California", "Mendoza", "Oregon"), state.provinces)
        state.selection = GuidedCriteria(country = "France")
        assertEquals(listOf("Bordeaux", "Burgundy"), state.provinces)
    }

    @Test fun defaultCatalogIsTheCuratedWineRegionsList() = runBlocking {
        val state = GuidedSelectionState(this, none)
        assertEquals(WineRegions.catalog.keys.sorted(), state.countries.sorted())
        assertTrue(state.countries.isNotEmpty())
    }

    @Test fun reviewsAndExtendedDbReceiveOnlyTheSameSelectedCriteria() = runBlocking {
        val received = mutableListOf<Map<String, String>>()
        val state = GuidedSelectionState(this,
            { received += it.constraints(); GuidedResult.Complete(emptyList()) },
            { received += it.constraints(); GuidedResult.Complete(emptyList()) })
        state.selection = GuidedCriteria(tannin = "Smooth")
        state.search()
        yield()
        assertEquals(listOf(mapOf("tannin" to "Smooth"), mapOf("tannin" to "Smooth")), received)
    }

    @Test fun searchShowsResultsAndBackToFormPreservesSelection() = runBlocking {
        val state = GuidedSelectionState(this, { GuidedResult.Complete(listOf(card)) })
        assertFalse(state.showResults)
        state.selection = criteria
        state.search()
        yield()
        assertTrue(state.showResults)
        assertEquals(GuidedResult.Complete(listOf(card)), state.database)
        state.backToForm()
        assertFalse(state.showResults)
        assertEquals(criteria, state.selection)
        assertEquals(criteria, state.submitted)
    }

    @Test fun reviewsShowWhileExtendedDbIsStillLoadingAndASecondSearchWaits() = runBlocking {
        val releaseExtended = CompletableDeferred<Unit>()
        val saved = card.copy(name = "Saved web wine")
        var databaseCalls = 0
        val state = GuidedSelectionState(this,
            { databaseCalls++; GuidedResult.Complete(listOf(card)) },
            { releaseExtended.await(); GuidedResult.Complete(listOf(saved)) })
        state.selection = criteria
        state.search()
        yield()
        assertEquals(GuidedResult.Complete(listOf(card)), state.database)
        assertEquals(GuidedResult.Loading(), state.extended)
        assertFalse(state.canSearch)
        state.search()
        assertEquals(1, databaseCalls)
        releaseExtended.complete(Unit)
        yield()
        assertEquals(GuidedResult.Complete(listOf(saved)), state.extended)
    }

    @Test fun databaseFailureLooksEmptyAndDoesNotCancelExtendedDb() = runBlocking {
        var logged = false
        val databaseGate = CompletableDeferred<Unit>()
        val state = GuidedSelectionState(this,
            { databaseGate.await(); error("parse failure") },
            { GuidedResult.Complete(listOf(card)) }, { logged = true })
        state.selection = criteria
        state.search()
        yield()
        assertEquals(GuidedResult.Complete(listOf(card)), state.extended)
        assertEquals(GuidedResult.Loading(), state.database)
        databaseGate.complete(Unit)
        yield()
        assertTrue(logged)
        assertEquals(GuidedResult.Complete(emptyList<WineSuggestion>()), state.database)
    }

    @Test fun obsoleteNonCancellableResponseCannotReplaceNewResults() = runBlocking {
        val obsolete = CompletableDeferred<Unit>()
        var calls = 0
        val newer = card.copy(name = "New search")
        val state = GuidedSelectionState(this, {
            calls++
            if (calls == 1) withContext(NonCancellable) { obsolete.await(); GuidedResult.Complete(listOf(card)) }
            else GuidedResult.Complete(listOf(newer))
        })
        state.selection = criteria
        state.search()
        yield()
        state.selection = criteria.copy(body = "Full-Bodied")
        assertTrue(state.canSearch)
        state.search()
        yield()
        assertEquals(GuidedResult.Complete(listOf(newer)), state.database)
        obsolete.complete(Unit)
        yield()
        yield()
        assertEquals(GuidedResult.Complete(listOf(newer)), state.database)
        assertEquals("Full-Bodied", state.submitted?.body)
    }

    @Test fun webSearchOnlyRunsWhenRequestedAndUsesTheSubmittedSelections() = runBlocking {
        val queries = mutableListOf<String>()
        val webCard = card.copy(name = "Web wine")
        val state = GuidedSelectionState(
            scope = this,
            databaseSearch = none,
            webSearch = { queries += it.webQuery; listOf(webCard) },
        )
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
        val state = GuidedSelectionState(
            scope = this,
            databaseSearch = none,
            webSearch = { error("offline") },
        )
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
