package com.sheldondesousa.uncork.data.knowledge

import com.sheldondesousa.uncork.model.WineAskExtras
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Ausone, Bordeaux, Sonoma, lees-stirring and "How is it made in Bordeaux?" misses from the bottle eval. */
class RagMissFixesTest {
    private fun asset(name: String) = File("src/main/assets/$name")
    private val internal = GrapeProfileInternal.fromJsonLines(asset("knowledge/grape_profile_internal.jsonl").readText())
    private val directory = WineriesDirectory.fromCsv(asset("knowledge/wineries_directory.csv").readText())
    private val production = WineProduction.fromJson(asset("knowledge/french_wine_production.json").readText())

    private fun extras(region: String = "") =
        WineAskExtras(internal, ownRegion = region, loadProduction = { production }, loadWineries = { directory }, loadReviews = { _, _ -> null })

    @Test fun aWineryIsFoundByItsCoreNameWithoutAWineryWord() = runBlocking {
        assertTrue(extras().forQuestion("Tell me about Château Ausone.").orEmpty().contains("Ausone"))
        assertTrue(extras().forQuestion("Tell me about Ausone.").orEmpty().contains("Ausone"))
    }

    @Test fun bordeauxAloneIsAPlace() = runBlocking {
        val text = extras().forQuestion("Name a few famous wineries in Bordeaux.").orEmpty()
        assertTrue(text, text.contains("Bordeaux"))
    }

    @Test fun sonomaCountyCoversEverySonomaRegion() = runBlocking {
        val text = extras().forQuestion("What wineries should I know in Sonoma County?").orEmpty()
        assertTrue(text, text.contains("Sonoma Valley") && text.contains("options"))
    }

    @Test fun lackOfAPlaceFallsBackToTheBottlesRegion() = runBlocking {
        val text = extras("Burgundy").forQuestion("What does lees stirring mean in Chardonnay making?").orEmpty()
        assertTrue(text, text.contains("Wine_Production"))
    }

    @Test fun aFollowUpRemembersTheGrape() = runBlocking {
        val e = extras()
        e.forQuestion("I like Merlot.")
        assertTrue(e.forQuestion("How is it made in Bordeaux?").orEmpty().contains("Wine_Production"))
    }
}
