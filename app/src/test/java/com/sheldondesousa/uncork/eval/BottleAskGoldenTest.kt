package com.sheldondesousa.uncork.eval

import com.sheldondesousa.uncork.data.knowledge.GrapeProfileInternal
import com.sheldondesousa.uncork.model.ChatFlowText
import com.sheldondesousa.uncork.model.AskLookupOutcome
import com.sheldondesousa.uncork.model.WineAskExtras
import com.sheldondesousa.uncork.model.WineryIntent
import com.sheldondesousa.uncork.model.WineFactsNote
import kotlinx.coroutines.runBlocking
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
        fun assets() = listOf(arrayOf("bottle_ask_v1.json"), arrayOf("bottle_ask_v2.json"), arrayOf("bottle_ask_v3.json"), arrayOf("bottle_ask_v4.json"))
    }
    private val set = JSONObject(File("src/debug/assets/eval/$assetName").readText())
    private val knowledge = GrapeProfileInternal.fromJsonLines(File("src/main/assets/knowledge/grape_profile_internal.jsonl").readText())

    @Test fun goldenProfilesStillMatchTheBundledSource() {
        val profiles = set.getJSONArray("profiles")
        assertEquals(if (assetName.contains("v4")) 67 else 15, profiles.length())
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
        assertEquals(if (assetName.contains("v3") || assetName.contains("v4")) 100 else 20, cases.length())
        val counts = mutableMapOf<String, Int>()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val category = case.getString("category")
            counts[category] = counts.getOrDefault(category, 0) + 1
            val own = knowledge.find(case.getJSONObject("bottle").getString("variety")).grapes
            val extras = WineAskExtras(knowledge, ownGrapes = own.map { it.grape }.toSet(), ownVariety = own.map { it.grape }, loadReviews = { _, _ -> null })
            val lookup = extras.lookup(case.getString("question"))
            if (category == "no_information") {
                assertEquals(case.getString("id"), ChatFlowText.NO_INFORMATION, lookup.refusal)
                assertEquals(ChatFlowText.NO_INFORMATION, case.getString("expected_answer"))
            } else {
                assertNull(case.getString("id"), lookup.refusal)
                val context = WineFactsNote.grapeBlock(own) + lookup.context.orEmpty()
                val sources = case.getJSONArray("source_grapes")
                for (j in 0 until sources.length()) assertTrue(context.contains(sources.getString(j)))
            }
        }
        val expectedCounts = when {
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
