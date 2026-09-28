package com.sheldondesousa.uncork.eval

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** One test conversation from `assets/eval/uncork_test_set_v1.json` — a bundled fixture, never shipped
 * outside a debug build. [turns] are fed to Gemma in order, one per chat turn. Extra authoring fields
 * on each entry (`judge`, `answer_key`, ...) are for the human/LLM grading pass and aren't read here. */
internal data class EvalTestCase(
    val id: String,
    val category: String,
    val turns: List<String>,
)

/** [promptVersion] comes from the test file's own `meta.prompt_version_under_test` when present,
 * so the output file's "prompt_version" always reflects what the file itself claims to be testing
 * rather than a hardcoded value that can drift out of sync. */
internal data class EvalTestSet(
    val promptVersion: String?,
    val cases: List<EvalTestCase>,
)

internal class EvalTestSetException(message: String) : Exception(message)

/** Reads and validates the bundled eval fixture. Accepts either a bare JSON array of
 * conversations, or an object with a top-level "conversations" array (and optional "meta").
 * Throws [EvalTestSetException] with a message pointing at exactly what's wrong, since this file
 * is hand-edited outside the type system. */
internal fun loadEvalTestSet(
    context: Context,
    assetPath: String = "eval/uncork_test_set_v1.json",
): EvalTestSet {
    val raw = try {
        context.assets.open(assetPath).bufferedReader().use { it.readText() }
    } catch (error: java.io.IOException) {
        throw EvalTestSetException("No eval test set found at assets/$assetPath.")
    }
    val parsed = try {
        JSONTokener(raw).nextValue()
    } catch (error: Exception) {
        throw EvalTestSetException("assets/$assetPath is not valid JSON.")
    }
    val (promptVersion, casesArray) = when (parsed) {
        is JSONArray -> null to parsed
        is JSONObject -> {
            val conversations = parsed.optJSONArray("conversations")
                ?: throw EvalTestSetException(
                    "assets/$assetPath is a JSON object but has no \"conversations\" array.",
                )
            val version = parsed.optJSONObject("meta")
                ?.optString("prompt_version_under_test")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            version to conversations
        }
        else -> throw EvalTestSetException(
            "assets/$assetPath must be a JSON array of conversations, or an object with a " +
                "\"conversations\" array.",
        )
    }
    val cases = buildList {
        for (index in 0 until casesArray.length()) {
            val entry = casesArray.optJSONObject(index)
                ?: throw EvalTestSetException("assets/$assetPath entry $index is not a JSON object.")
            val id = entry.optString("id").trim()
            if (id.isEmpty()) throw EvalTestSetException("assets/$assetPath entry $index is missing \"id\".")
            val category = entry.optString("category").trim()
            val turnsArray = entry.optJSONArray("turns")
                ?: throw EvalTestSetException("Test case \"$id\" is missing a \"turns\" array.")
            val turns = buildList {
                for (turnIndex in 0 until turnsArray.length()) {
                    add(turnsArray.optString(turnIndex))
                }
            }
            if (turns.isEmpty()) throw EvalTestSetException("Test case \"$id\" has an empty \"turns\" array.")
            add(EvalTestCase(id = id, category = category, turns = turns))
        }
    }
    return EvalTestSet(promptVersion = promptVersion, cases = cases)
}

/** One (test case, run) result, matching the required output schema exactly:
 * `{"id": ..., "run": ..., "replies": [...]}` per turn. */
internal data class EvalResult(
    val id: String,
    val run: Int,
    val replies: List<String>,
)

internal fun writeEvalResults(promptVersion: String, model: String, results: List<EvalResult>): String {
    val resultsArray = JSONArray()
    results.forEach { result ->
        resultsArray.put(
            JSONObject().apply {
                put("id", result.id)
                put("run", result.run)
                put("replies", JSONArray(result.replies))
            },
        )
    }
    return JSONObject().apply {
        put("prompt_version", promptVersion)
        put("model", model)
        put("results", resultsArray)
    }.toString(2)
}
