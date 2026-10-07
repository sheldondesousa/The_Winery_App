package com.sheldondesousa.uncork.data.knowledge

import com.sheldondesousa.uncork.model.WineAskExtras
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The retrieval half of Docs/Evals/uncork_rag_test_set_v1.json, run without Gemma: every turn of every conversation goes
 * through the same lookups the open chat uses, and what would be added to the prompt is saved as `retrieved_chunks`
 * (shape from uncork_rag_judge_prompt_v1.md) together with the judge file's code checks `retrieval_hit` and
 * `irrelevant_retrieval`. Replies are not produced here, so the judge dimensions are not run.
 *
 * Not covered, because they need the device database: review samples, Grape_Profile_Kaggle_Extracted rows, other countries.
 */
class RagRetrievalEvalTest {
    private fun asset(name: String) = File("src/main/assets/$name")
    private val internal = GrapeProfileInternal.fromJsonLines(asset("knowledge/grape_profile_internal.jsonl").readText())
    private val directory = WineriesDirectory.fromCsv(asset("knowledge/wineries_directory.csv").readText())
    private val production = WineProduction.fromJson(asset("knowledge/french_wine_production.json").readText())

    // Same wiring as WineAskContext.extrasForChat() for Chat's open conversation.
    private fun chatExtras() = WineAskExtras(
        knowledge = internal, loadProduction = { production }, loadWineries = { directory }, loadReviews = { _, _ -> null },
    )

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test fun runEveryConversationThroughRetrievalAndSaveTheChunks() = runBlocking {
        val set = JSONObject(File("../Docs/Evals/uncork_rag_test_set_v1.json").readText())
        val conversations = set.getJSONArray("conversations")
        val out = StringBuilder()
        for (i in 0 until conversations.length()) {
            val c = conversations.getJSONObject(i)
            val extras = chatExtras()
            val turns = JSONArray()
            var finalChunks = emptyList<String>()
            val allChunks = mutableListOf<String>()
            strings(c.getJSONArray("turns")).forEach { user ->
                // Chat answers a request for a list of wineries itself; anything else goes to Gemma with extra blocks.
                val listed = extras.wineryList(user)
                val chunks = listed?.let { listOf(it) } ?: extras.forQuestion(user)?.split("\n\n").orEmpty().filter { it.isNotBlank() }
                finalChunks = chunks
                allChunks += chunks
                turns.put(JSONObject().put("user", user).put("reply", JSONObject.NULL).put("retrieved_chunks", JSONArray(chunks)).put("kotlin_handled", listed != null))
            }
            val keywords = strings(c.getJSONArray("retrieval_keywords"))
            fun hit(chunks: List<String>): Boolean? =
                if (keywords.isEmpty()) null else keywords.count { k -> chunks.any { it.contains(k, ignoreCase = true) } } * 2 >= keywords.size
            val expectsNone = c.optString("retrieval_expected") == "none"
            val checks = JSONObject()
                .put("retrieval_hit_final_turn", hit(finalChunks) ?: JSONObject.NULL)
                .put("retrieval_hit_any_turn", hit(allChunks) ?: JSONObject.NULL)
                .put("irrelevant_retrieval", if (expectsNone) allChunks.isNotEmpty() else JSONObject.NULL)
                .put("final_turn_chunk_count", finalChunks.size)
            out.appendLine(
                JSONObject().put("id", c.getString("id")).put("category", c.getString("category")).put("rag_enabled", true)
                    .put("turns", turns).put("checks", checks).toString(),
            )
        }
        File("../Docs/Evals/results").mkdirs()
        File("../Docs/Evals/results/rag-retrieval-eval-2026-10-05.jsonl").writeText(out.toString())
        assertEquals(conversations.length(), out.lines().count { it.isNotBlank() })
    }
}
