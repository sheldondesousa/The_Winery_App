package com.sheldondesousa.uncork.eval

import com.sheldondesousa.uncork.data.knowledge.GrapeProfileInternal
import com.sheldondesousa.uncork.model.ChatFlowText
import com.sheldondesousa.uncork.model.AskLookupOutcome
import com.sheldondesousa.uncork.model.WineAskExtras
import com.sheldondesousa.uncork.model.WineryIntent
import com.sheldondesousa.uncork.model.WineFactsNote
import kotlinx.coroutines.runBlocking
import com.sheldondesousa.uncork.model.KaggleExtractedProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class BottleAskGoldenTest(private val assetName: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun assets() = listOf(arrayOf("bottle_ask_v1.json"), arrayOf("bottle_ask_v2.json"), arrayOf("bottle_ask_v3.json"), arrayOf("bottle_ask_v4.json"), arrayOf("bottle_ask_v5.json"), arrayOf("bottle_ask_v6.json"))
    }
    private val set = JSONObject(File("src/debug/assets/eval/$assetName").readText())
    private val knowledge = GrapeProfileInternal.fromJsonLines(File("src/main/assets/knowledge/grape_profile_internal.jsonl").readText())

    // The app reads Grape_Profile_Kaggle_Extracted through a database; here the same asset is filtered the same way
    // (country, grape spellings, any of body/tannin/acidity, province order, three rows) so lookups see real rows.
    private val extractedRows: List<JSONObject> = JSONArray(File("src/main/assets/grape_profile_kaggle_extracted.json").readText())
        .let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    private val kaggle: suspend (List<String>, String) -> List<KaggleExtractedProfile> = { spellings, country ->
        extractedRows.filter { row ->
            row.getString("country").equals(country, ignoreCase = true) &&
                spellings.any { it.equals(row.getString("variety"), ignoreCase = true) } &&
                listOf("body", "tannin", "acidity").any { !row.isNull(it) && row.getString(it).isNotBlank() }
        }.sortedBy { it.getString("province") }.take(3).map { row ->
            KaggleExtractedProfile(
                body = row.optString("body").takeIf { it.isNotBlank() && it != "null" }, tannin = row.optString("tannin").takeIf { it.isNotBlank() && it != "null" },
                acidity = row.optString("acidity").takeIf { it.isNotBlank() && it != "null" },
                flavorNotes = row.optJSONArray("flavor_notes")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                province = row.getString("province"), country = row.getString("country"))
        }
    }

    @Test fun goldenProfilesStillMatchTheBundledSource() {
        val profiles = set.getJSONArray("profiles")
        assertEquals(if (assetName.contains("v4") || assetName.contains("v5") || assetName.contains("v6")) 67 else 15, profiles.length())
        for (i in 0 until profiles.length()) {
            val saved = profiles.getJSONObject(i)
            val source = File("src/main/assets/knowledge/grape_profile_internal.jsonl").readLines()
                .map { JSONObject(it) }.single { it.getString("grape") == saved.getString("grape") }
            val actual = GrapeProfileInternal.fromJsonLines(source.toString()).find(saved.getString("grape")).grapes.single()
            assertEquals(saved.getString("summary"), actual.summary)
            assertEquals(saved.getString("aromas"), actual.aromas)
            assertEquals(saved.getString("body"), actual.body)
            assertEquals(saved.getString("tannin"), actual.tannin)
            assertEquals(saved.getString("acidity"), actual.acidity)
        }
    }

    @Test fun unknownNamesAreNotMistakenForReferencesToTheSelectedGrape() = runBlocking {
        fun extras() = WineAskExtras(knowledge, ownGrapes = setOf("Merlot"), ownVariety = listOf("Merlot"), loadReviews = { _, _ -> null })
        listOf("Tell me about Zorvella.", "What can your grape notes tell me about Kelvaris?", "Describe Kelvaris.").forEach {
            assertEquals(it, ChatFlowText.NO_INFORMATION, extras().lookup(it).refusal)
        }
        listOf("Tell me about it", "Tell me more", "Describe it", "Tell me about Merlot").forEach {
            assertNull(it, extras().lookup(it).refusal)
        }
        assertEquals("Grenache Noir", knowledge.find("Garnacha").grapes.single().grape)
    }

    @Test fun missingNamesAreRefusedAndSupportedCasesHaveSourceNotes() = runBlocking {
        val cases = set.getJSONArray("cases")
        assertEquals(when { assetName.contains("v5") || assetName.contains("v6") -> 300; assetName.contains("v3") || assetName.contains("v4") -> 100; else -> 20 }, cases.length())
        val counts = mutableMapOf<String, Int>()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val category = case.getString("category")
            counts[category] = counts.getOrDefault(category, 0) + 1
            val bottle = case.getJSONObject("bottle")
            val variety = bottle.getString("variety")
            val own = knowledge.find(variety).grapes
            // As in WineAskContext.extrasFor: the bottle's own grape, country and region are known even when the grape
            // has no internal profile (it then comes from Grape_Profile_Kaggle_Extracted).
            fun place(key: String) = bottle.getString(key).takeUnless { it.equals("Unknown", ignoreCase = true) }.orEmpty()
            val extras = WineAskExtras(knowledge, ownGrapes = own.map { it.grape }.toSet(),
                ownVariety = if (own.isNotEmpty()) own.map { it.grape } else listOf(variety),
                ownCountry = place("country"), ownRegion = place("province"), loadKaggleExtracted = kaggle, loadReviews = { _, _ -> null })
            val lookup = extras.lookup(case.getString("question"))
            if (category == "no_information") {
                // v5 marks the unsupported questions Kotlin's lookup did not refuse when the set was built; they reach Gemma on
                // purpose. If Kotlin is fixed this fails, which is the reminder to update the flag.
                if (case.optBoolean("expected_kotlin_miss")) assertNull(case.getString("id"), lookup.refusal)
                else assertEquals(case.getString("id"), ChatFlowText.NO_INFORMATION, lookup.refusal)
                assertEquals(ChatFlowText.NO_INFORMATION, case.getString("expected_answer"))
            } else {
                assertNull(case.getString("id"), lookup.refusal)
                val context = WineFactsNote.grapeBlock(own) + lookup.context.orEmpty()
                val sources = case.getJSONArray("source_grapes")
                for (j in 0 until sources.length()) assertTrue(context.contains(sources.getString(j)))
            }
        }
        val expectedCounts = when {
            assetName.contains("v6") -> mapOf("supported" to 190, "false_claim" to 80, "no_information" to 15, "needs_place" to 15)
            assetName.contains("v5") -> mapOf("supported" to 100, "no_information" to 100, "false_claim" to 100)
            assetName.contains("v4") -> mapOf("supported" to 50, "no_information" to 25, "false_claim" to 25)
            assetName.contains("v3") -> mapOf("supported" to 50, "no_information" to 25, "false_claim" to 25)
            else -> mapOf("supported" to 10, "no_information" to 5, "false_claim" to 5)
        }
        assertEquals(expectedCounts, counts)
    }
    @Test fun lowercaseNamesAndCapitalisationVariantsAreExplicitMisses() = runBlocking {
        listOf(
            "What can you tell me about zenvaril?", "Which aromas does molvessa have?",
            "What body would ravnexia have?", "Is there a tannin level for kelquorin?", "How acidic is delvessa?",
            "Describe florvessa.", "Which aromas are recorded for belvarin?",
        ).forEach { query ->
            listOf(query, query.uppercase(), query.lowercase()).forEach { variant ->
                val extras = WineAskExtras(knowledge, ownGrapes = setOf("Merlot"), ownVariety = listOf("Merlot"), loadReviews = { _, _ -> null })
                val result = extras.lookup(variant)
                assertEquals(variant, ChatFlowText.NO_INFORMATION, result.refusal)
                assertEquals(AskLookupOutcome.NO_INFORMATION, result.outcome)
                assertNull(result.context)
            }
        }
    }

    @Test fun basedOnProfileNeverTriggersWineryRetrieval() = runBlocking {
        var wineryCalls = 0
        val extras = WineAskExtras(knowledge, ownGrapes = setOf("Merlot"), ownVariety = listOf("Merlot"),
            loadGrapeWineries = { _, _, _, _ -> wineryCalls++; null }, loadReviews = { _, _ -> null })
        val query = "Can I describe Garnacha as medium-bodied with high acidity based on your profile?"
        assertFalse(WineryIntent.asksAboutWineries(query))
        assertNull(extras.wineryList(query))
        val result = extras.lookup(query)
        assertNull(result.refusal)
        assertTrue(result.context.orEmpty().contains("Very full"))
        assertEquals(0, wineryCalls)
        assertTrue(WineryIntent.asksAboutWineries("Where is this producer based in France?"))
    }

    @Test fun nullExtraContextDistinguishesKnownFactsFromMissingInformation() = runBlocking {
        val extras = WineAskExtras(knowledge, ownGrapes = setOf("Merlot"), ownVariety = listOf("Merlot"), loadReviews = { _, _ -> null })
        val known = extras.lookup("What body does Merlot have?")
        assertNull(known.refusal)
        assertEquals(AskLookupOutcome.ALREADY_IN_CONVERSATION, known.outcome)
        val greeting = extras.lookup("hello")
        assertNull(greeting.refusal)
        assertEquals(AskLookupOutcome.NO_EXTRA_NEEDED, greeting.outcome)
        assertEquals(AskLookupOutcome.NO_INFORMATION, extras.lookup("What body would florvessa have?").outcome)
        listOf("How acidic is it?", "Describe the body of this wine", "Tell me about this wine's ageing potential", "Describe its aromas", "Tell me about tannins").forEach {
            assertNull(it, extras.lookup(it).refusal)
        }
    }

}
