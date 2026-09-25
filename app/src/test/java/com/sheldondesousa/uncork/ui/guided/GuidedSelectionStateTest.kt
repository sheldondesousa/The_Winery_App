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

    @Test fun catalogProvidesAllCountriesAndAlphabeticalCascadingProvinces() = runBlocking {
        val state = GuidedSelectionState(this, { emptyList() }, { GuidedResult.Complete(emptyList()) },
            locations = linkedMapOf("US" to listOf("Oregon", "California"),
                "France" to listOf("Burgundy", "Bordeaux"), "Argentina" to listOf("Mendoza")))
        assertEquals(listOf("Argentina", "France", "US"), state.countries)
        assertEquals(listOf("Bordeaux", "Burgundy", "California", "Mendoza", "Oregon"), state.provinces)
        state.selection = GuidedCriteria(country = "France")
        assertEquals(listOf("Bordeaux", "Burgundy"), state.provinces)
    }

    @Test fun defaultCatalogIsTheCuratedWineRegionsList() = runBlocking {
        val state = GuidedSelectionState(this, { emptyList() }, { GuidedResult.Complete(emptyList()) })
        assertEquals(WineRegions.catalog.keys.sorted(), state.countries.sorted())
        assertTrue(state.countries.isNotEmpty())
    }

    @Test fun bothSourcesReceiveOnlyTheSameSelectedCriteria() = runBlocking {
        val received = mutableListOf<Map<String, String>>()
        val state = GuidedSelectionState(this,
            { received += it.constraints(); emptyList() },
            { received += it.constraints(); GuidedResult.Complete(emptyList()) })
        state.selection = GuidedCriteria(tannin = "Smooth")
        state.search()
        yield()
        assertEquals(listOf(mapOf("tannin" to "Smooth"), mapOf("tannin" to "Smooth")), received)
    }

    @Test fun searchShowsResultsAndBackToFormPreservesSelection() = runBlocking {
        val state = GuidedSelectionState(this, { listOf(card) }, { GuidedResult.Complete(listOf(card)) })
        assertFalse(state.showResults)
        state.selection = criteria
        state.search()
        yield()
        assertTrue(state.showResults)
        state.backToForm()
        assertFalse(state.showResults)
        assertEquals(criteria, state.selection)
        assertEquals(criteria, state.submitted)
    }

    @Test fun databaseCompletesWhileGemmaIsPendingAndRetryOnlyCallsGemma() = runBlocking {
        val releaseGemma = CompletableDeferred<Unit>()
        var gemmaCalls = 0
        var databaseCalls = 0
        val state = GuidedSelectionState(this, {
            gemmaCalls++
            if (gemmaCalls == 1) {
                releaseGemma.await()
                error("model unavailable")
            }
            listOf(card)
        }, { databaseCalls++; GuidedResult.Complete(listOf(card)) })
        state.selection = criteria
        state.search()
        yield()
        assertEquals(GuidedResult.Loading(), state.gemma)
        assertEquals(GuidedResult.Complete(listOf(card)), state.database)
        assertFalse(state.canSearch)
        state.search()
        assertEquals(1, databaseCalls)
        releaseGemma.complete(Unit)
        yield()
        assertEquals(GuidedResult.Error, state.gemma)
        state.selection = criteria.copy(body = "Full-Bodied")
        state.retryGemma()
        state.retryGemma()
        yield()
        assertEquals(2, gemmaCalls)
        assertEquals(1, databaseCalls)
        assertEquals(criteria, state.submitted)
        assertEquals(GuidedResult.Complete(listOf(card)), state.gemma)
    }

    @Test fun databaseFailureLooksEmptyAndDoesNotCancelGemma() = runBlocking {
        var logged = false
        val databaseGate = CompletableDeferred<Unit>()
        val state = GuidedSelectionState(this,
            { listOf(card) }, { databaseGate.await(); error("parse failure") }, { logged = true })
        state.selection = criteria
        state.search()
        yield()
        assertEquals(GuidedResult.Complete(listOf(card)), state.gemma)
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
            if (calls == 1) withContext(NonCancellable) { obsolete.await(); listOf(card) }
            else listOf(newer)
        }, { GuidedResult.Complete(emptyList()) })
        state.selection = criteria
        state.search()
        yield()
        state.selection = criteria.copy(body = "Full-Bodied")
        assertTrue(state.canSearch)
        state.search()
        yield()
        assertEquals(GuidedResult.Complete(listOf(newer)), state.gemma)
        obsolete.complete(Unit)
        yield()
        yield()
        assertEquals(GuidedResult.Complete(listOf(newer)), state.gemma)
        assertEquals("Full-Bodied", state.submitted?.body)
    }

    @Test fun gemmaPublishesEachCompactCardWhileTheSearchIsStillRunning() = runBlocking {
        val publishSecond = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val second = card.copy(name = "Second wine")
        val state = GuidedSelectionState(
            scope = this,
            gemmaSearch = { _, onUpdate ->
                onUpdate(listOf(card))
                publishSecond.await()
                onUpdate(listOf(card, second))
                finish.await()
                listOf(card, second)
            },
            databaseSearch = { GuidedResult.Complete(emptyList()) },
        )
        state.selection = criteria

        state.search()
        yield()
        assertEquals(GuidedResult.Loading(listOf(card)), state.gemma)
        publishSecond.complete(Unit)
        yield()
        assertEquals(GuidedResult.Loading(listOf(card, second)), state.gemma)
        finish.complete(Unit)
        yield()
        assertEquals(GuidedResult.Complete(listOf(card, second)), state.gemma)
    }
}
