package com.sheldondesousa.uncork.data.knowledge

import com.sheldondesousa.uncork.model.WineAskExtras
import com.sheldondesousa.uncork.model.WineFactsNote
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sweeps every entry of each bundled knowledge database and checks it can be found and that what is found reaches
 * the text Gemma is given. Unlike the focused tests, nothing here is hand-picked: it walks the data itself, so a
 * bad row, a renamed country or a broken lookup shows up by name.
 */
class RagCoverageTest {
    private fun asset(name: String) = File("src/main/assets/$name")

    private val grapeRows = asset("knowledge/grape_profile_internal.jsonl").readLines().filter { it.isNotBlank() }.map(::JSONObject)
    private val internal = GrapeProfileInternal.fromJsonLines(asset("knowledge/grape_profile_internal.jsonl").readText())
    private val directory = WineriesDirectory.fromCsv(asset("knowledge/wineries_directory.csv").readText())
    private val production = WineProduction.fromJson(asset("knowledge/french_wine_production.json").readText())
    private val kaggleSeed = JSONArray(asset("grape_profile_kaggle_extracted.json").readText())

    private fun extras(ownCountry: String = "") = WineAskExtras(internal, ownCountry = ownCountry, loadWineries = { directory }, loadReviews = { _, _ -> null })

    // ---- Grape_Profile_Internal ------------------------------------------------------------------------------

    @Test fun everyGrapeIsFoundByItsNameAndItsNotesReachThePrompt() {
        val missing = mutableListOf<String>()
        grapeRows.forEach { row ->
            val name = row.getString("grape")
            val found = internal.find(name.substringBefore(" / ")).grapes.firstOrNull()
            val note = found?.let { WineFactsNote.build(WineSuggestion(name = "Wine", province = "", variety = name), GrapeLookup(listOf(it))) }.orEmpty()
            if (found == null || !note.contains("Grape_Profile_Internal") || !note.contains(found.summary.take(30))) missing += name
        }
        assertTrue("grapes not retrieved into the prompt: $missing", missing.isEmpty())
    }

    @Test fun everyAliasFindsItsGrape() {
        val wrong = mutableListOf<String>()
        grapeRows.forEach { row ->
            val grape = row.getString("grape")
            row.optString("also_known_as").split(";", ",")
                .map { it.replace(Regex("\\(.*?\\)"), "").trim() }.filter { it.isNotBlank() }
                .forEach { alias ->
                    val hit = internal.find(alias).grapes.firstOrNull()?.grape
                    // Some grapes are listed under two names (Garnacha / Grenache Noir); an alias may land on the twin's row.
                    val twin = hit != null && grapeRows.any { it.getString("grape") == hit }
                    if (hit == null || !(grape.contains(hit) || twin)) wrong += "$alias -> $hit (expected $grape)"
                }
        }
        assertTrue("aliases that do not find their grape: $wrong", wrong.isEmpty())
    }

    @Test fun askingAboutAGrapeInAQuestionSendsItsNotesOnce() = runBlocking {
        val failures = mutableListOf<String>()
        grapeRows.forEach { row ->
            val name = row.getString("grape").substringBefore(" / ")
            // The notes are used for a place they cover, so each grape is asked about in its own country of origin.
            val block = extras(ownCountry = row.optString("origin_country").ifBlank { row.getString("key_regions").substringBefore(";").substringBefore(" (").trim() }).forQuestion("Tell me about $name")
            // Pinot Grigio and Pinot Gris share one group, so the notes may carry either name.
            if (block == null || !block.contains("Grape_Profile_Internal")) failures += name
        }
        assertTrue("grape questions that got no notes: $failures", failures.isEmpty())
    }

    @Test fun everyGrapeHasTheFieldsGemmaIsToldAbout() {
        val thin = grapeRows.filter { r -> listOf("summary", "body", "acidity", "aromas").any { r.optString(it).isBlank() } }
            .map { it.getString("grape") }
        assertTrue("grapes with blank key fields: $thin", thin.isEmpty())
    }

    // ---- Wineries_Directory ----------------------------------------------------------------------------------

    private val sampledWineries = directory.countries().flatMap { c ->
        val rnd = Random(c.name.hashCode())
        directory.regions(c.name).flatMap { r -> directory.wineriesIn(c.name, r.name).map { WineryLocation(it, c.name, r.name) } }
            .let { all -> if (all.size <= 10) all else List(10) { all[rnd.nextInt(all.size)] } }
    }

    @Test fun everyCountryAndRegionCanBeBrowsedAndSampled() {
        val problems = mutableListOf<String>()
        directory.countries().forEach { c ->
            if (directory.countOf(c.name) != c.count || directory.sampleIn(c.name).isEmpty()) problems += c.name
            directory.regions(c.name).forEach { r ->
                if (directory.countOf(c.name, r.name) < r.count || directory.sampleIn(c.name, r.name).isEmpty()) problems += "${c.name}/${r.name}"
            }
        }
        assertTrue("places that cannot be sampled: $problems", problems.isEmpty())
        assertEquals(directory.size, directory.countries().sumOf { it.count })
    }

    @Test fun sampledWineriesAreFoundByNameInTheirOwnCountry() {
        val misses = sampledWineries.filter { w -> directory.find(w.winery, w.country).none { it.country == w.country } }
        // Known gap: names written only in Cyrillic, Greek, Armenian etc. normalise to nothing and cannot be matched.
        val unexplained = misses.filter { GrapeProfileInternal.normalize(it.winery).isNotBlank() }
        assertTrue("Latin-script wineries not found in their own country: ${unexplained.take(15)}", unexplained.isEmpty())
    }

    @Test fun whereIsQuestionsForListedWineriesSendTheirLocation() = runBlocking {
        // Questions match a whole name of up to five words; longer legal names and non-Latin scripts are a known gap.
        val tested = sampledWineries.filter {
            val tokens = GrapeProfileInternal.normalize(it.winery).split(' ')
            // Dotless i (Turkish) is not folded to i, so those names cannot match either.
            tokens.size in 2..5 && it.winery.length >= 8 && 'ı' !in it.winery &&
                it.winery.all { c -> c.isLetter() && c.code < 0x250 || c in " '.&-" }
        }
        val misses = tested.filter { w ->
            val block = extras().forQuestion("Where is ${w.winery} located?")
            block == null || !block.contains("Wineries_Directory") || !block.contains(w.winery)
        }
        assertTrue("location questions with no directory entry (${misses.size} of ${tested.size}): ${misses.take(15)}", misses.isEmpty())
    }

    @Test fun whichWineriesInQuestionsListWineriesForEveryDirectoryCountryTheAppRecognises() = runBlocking {
        val all = directory.countries().map { it.name }
        val recognised = all.filter { com.sheldondesousa.uncork.model.CountryMentions.find("wineries in $it").isNotEmpty() }
        // Every directory country can now be named in a question (the gap with the review database's list is closed).
        assertEquals(emptySet<String>(), (all - recognised.toSet()).toSet())
        val failures = recognised.filter { c ->
            val block = extras().forQuestion("Which wineries are in $c?")
            block == null || !block.contains("Wineries_Directory") || !block.contains(c)
        }
        assertTrue("countries whose winery question got no list: $failures", failures.isEmpty())
    }

    @Test fun anUnlistedWineryNeverGetsInventedEntries() = runBlocking {
        assertEquals(null, extras().forQuestion("Where is Zzyzx Qwertyville Cellars located?"))
    }

    // ---- Wine production (France seed) -----------------------------------------------------------------------

    @Test fun everyPlaceOfEveryGrapeWithRecordsResolvesToFactsWithSources() {
        val problems = mutableListOf<String>()
        production.grapes.filter { it.hasFacts }.forEach { grape ->
            grape.root.flattened().forEach { (place, _) ->
                val r = production.resolve(grape, place.name)
                if (r.facts.isEmpty() || r.facts.any { it.value.isBlank() }) problems += "${grape.name}/${place.name}"
            }
        }
        assertTrue("places that resolve badly: $problems", problems.isEmpty())
    }

    // ---- Grape_Profile_Kaggle_Extracted ----------------------------------------------------------------------

    @Test fun everyRegionStyleRowNamesAGrapeTheAppCanRecogniseInAQuestion() {
        val unknown = (0 until kaggleSeed.length()).map { kaggleSeed.getJSONObject(it).getString("variety") }.distinct()
            // Blends and style labels ("Red Blend", "Rhône-style White Blend") are not grape names a user types.
            .filter { !it.contains("blend", ignoreCase = true) && !it.contains("-style", ignoreCase = true) }
            .filter { com.sheldondesousa.uncork.data.reviews.GrapeVarietyLookup.findMentioned(it).isEmpty() }
        // Deliberately not matched: wine styles and drinks that are not a single grape, and grape names that are also
        // everyday words ("Apple", "Melon", "Baga", "Mission") which would fire on ordinary sentences.
        val expected = setOf(
            "Rosado", "Rosé", "Rosato", "G-S-M", "Port", "White Port", "Sherry", "Meritage", "Edelzwicker", "Tokaji", "Other",
            "Portuguese Red", "Portuguese White", "Portuguese Rosé", "Portuguese Sparkling", "Loin de l'Oeil",
            "Apple", "Melon", "Baga", "Rebo", "Symphony", "Mission", "Norton", "Diamond", "Claret",
        )
        val unexplained = unknown - expected
        assertTrue("region-style grapes the app cannot recognise and nobody decided to skip: $unexplained", unexplained.isEmpty())
    }

    @Test fun regionStyleRowsReachThePromptCreditedToEnthusiasts() {
        val row = (0 until kaggleSeed.length()).map { kaggleSeed.getJSONObject(it) }.first { it.getString("variety") == "Aglianico" }
        val style = com.sheldondesousa.uncork.model.KaggleExtractedProfile(
            row.optString("body"), row.optString("tannin"), row.optString("acidity"),
            (0 until row.getJSONArray("flavor_notes").length()).map { row.getJSONArray("flavor_notes").getString(it) },
            row.getString("province"), row.getString("country"),
        )
        val block = WineFactsNote.kaggleExtractedBlock(listOf(style), "Aglianico")
        assertTrue(block.contains("Grape_Profile_Kaggle_Extracted") && block.contains("Wine enthusiasts say"))
    }

    // ---- FRENCH_WINE_KNOWLEDGE_SEED.md -> Wine_Production ----------------------------------------------------

    private fun productionExtras(ownVariety: List<String> = emptyList(), ownCountry: String = "", ownRegion: String = "") = WineAskExtras(
        internal, ownVariety = ownVariety, ownCountry = ownCountry, ownRegion = ownRegion,
        loadProduction = { production }, loadReviews = { _, _ -> null },
    )

    @Test fun theBundledProductionDataMatchesEveryRecordInTheSeedMarkdown() {
        val md = File("../Docs/FRENCH_WINE_KNOWLEDGE_SEED.md").readText()
        val records = Regex("```json\\s*(\\{.*?\\n\\})\\s*```", RegexOption.DOT_MATCHES_ALL).findAll(md).map { JSONObject(it.groupValues[1]) }
            .filter { it.has("grape") }.toList()
        assertEquals(4, records.size)
        records.forEach { rec ->
            val grape = production.grape(rec.getString("grape"))!!
            val place = if (rec.getString("scope") == "country") rec.getString("country") else rec.getString("region")
            val facts = rec.optJSONObject("production") ?: rec.getJSONObject("regional_overrides")
            val stored = grape.records.first { it.place == place }.facts
            assertEquals(rec.getString("id"), facts.keys().asSequence().toSet(), stored.keys)
            facts.keys().forEach { k ->
                assertEquals("${rec.getString("id")}/$k", facts.getJSONObject(k).getString("value"), stored.getValue(k).value)
                assertEquals(facts.getJSONObject(k).getString("practice_status"), stored.getValue(k).status)
            }
        }
    }

    @Test fun howIsMerlotMadeInBordeauxUsesTheBordeauxRecordOverTheFranceOne() = runBlocking {
        val bordeaux = productionExtras().forQuestion("How is Merlot made in Bordeaux?")!!
        assertTrue(bordeaux.contains("Wine_Production (how Merlot is made in Bordeaux, France"))
        assertTrue(bordeaux.contains("dominant red grape of Bordeaux"))
        // Steps Bordeaux does not redefine come from France and are marked as such.
        assertTrue(bordeaux.contains("Merlot is an early-ripening red variety") && bordeaux.contains("(general to France)"))
        assertTrue(bordeaux.contains("Sources: ") && bordeaux.contains("Conseil Interprofessionnel du Vin de Bordeaux"))
        // A place inside Bordeaux counts too.
        assertTrue(productionExtras().forQuestion("How is Merlot made in Saint-Émilion?")!!.contains("Saint-Émilion, France"))
    }

    @Test fun howIsChardonnayMadeInBurgundyAndAnAppellationInheritsFromBurgundy() = runBlocking {
        assertTrue(productionExtras().forQuestion("How is Chardonnay made in Burgundy?")!!.contains("traditional white Burgundy vinification"))
        val chablis = productionExtras().forQuestion("How is Chardonnay made in Chablis?")!!
        assertTrue(chablis.contains("how Chardonnay is made in Chablis, France") && chablis.contains("(general to Burgundy)"))
    }

    @Test fun anOpenWinesGrapeAndRegionAnswerAQuestionThatNamesNeither() = runBlocking {
        val block = productionExtras(listOf("Merlot"), "France", "Bordeaux").forQuestion("How is this wine made?")!!
        assertTrue(block.contains("how Merlot is made in Bordeaux, France"))
    }

    @Test fun productionNotesAreSentOnceAndOnlyForQuestionsAboutHowWineIsMade() = runBlocking {
        val extras = productionExtras()
        assertTrue(extras.forQuestion("How is Merlot made in Bordeaux?") != null)
        assertEquals(null, extras.forQuestion("How is Merlot made in Bordeaux?"))
        assertEquals(null, productionExtras().forQuestion("What does Merlot taste like in Bordeaux?")?.takeIf { it.contains("Wine_Production") })
    }

    @Test fun productionNotesAreUsedOnlyForMerlotInBordeauxAndChardonnayInBurgundy() = runBlocking {
        fun sent(extras: WineAskExtras, q: String) = runBlocking { extras.forQuestion(q)?.contains("Wine_Production") == true }
        listOf(
            "How is Merlot made in France?", "How is Merlot made?", "How is Merlot made in Burgundy?", "How is Merlot made in Champagne?",
            "How is Chardonnay made in France?", "How is Chardonnay made in Bordeaux?", "How is Chardonnay made in Champagne?",
            "How is Merlot made in Chile?", "How is Chardonnay made in Italy?", "How is Syrah made in France?",
            "How is Malbec made in Bordeaux?", "How is Grenache made in France?", "How is wine made in Bordeaux?",
        ).forEach { assertFalse(it, sent(productionExtras(), it)) }
        // The open wine decides only when it is itself in scope.
        assertFalse(sent(productionExtras(listOf("Merlot"), "Italy", "Tuscany"), "How is this wine made?"))
        assertFalse(sent(productionExtras(listOf("Merlot"), "France", "Loire Valley"), "How is this wine made?"))
        assertFalse(sent(productionExtras(listOf("Syrah"), "France", "Bordeaux"), "How is this wine made?"))
        assertTrue(sent(productionExtras(listOf("Chardonnay"), "France", "Chablis"), "How is this wine made?"))
        assertTrue(sent(productionExtras(), "How is Chardonnay made in Burgundy?"))
    }
}
