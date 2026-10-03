package com.sheldondesousa.uncork.eval

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.sheldondesousa.uncork.BuildConfig
import com.sheldondesousa.uncork.model.GemmaConversationResponder
import com.sheldondesousa.uncork.model.GemmaEvalTrace
import com.sheldondesousa.uncork.model.ModelFileManager
import com.sheldondesousa.uncork.model.WinePreferences
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Random

private const val LOG_TAG = "GemmaEval"
private const val BATCH_SIZE = 25

/** Pause flag the debug screen flips; cancelling is done by cancelling the coroutine running [run]. */
internal class EvalRunController {
    @Volatile
    var paused = false
}

/**
 * Debug-only runner for the Stage 1 card evals ("Gemma without interference").
 *
 * Eval A runs the app's real Call 1 ([GemmaConversationResponder.synthesizeCards]) from the
 * selections in `payload_dict`. Eval B runs the real Call 2 ([GemmaConversationResponder.enrichWineDetails])
 * from the card built out of `known_facts_sent_to_gemma`. Every case gets a fresh Gemma conversation
 * (both real call paths create their own). The runner adds no repair or normalisation of its own:
 * [GemmaEvalTrace] only records what the app already does between Gemma's raw reply and the card.
 *
 * One JSONL line is appended per call as soon as it finishes, so a crash or cancel keeps everything
 * finished so far. `run_meta.json` is rewritten after every batch of [BATCH_SIZE] cases.
 */
internal object GemmaCardsDetailEvalRunner {
    suspend fun run(
        context: Context,
        controller: EvalRunController,
        evals: Set<String> = setOf("A", "B"),
        onProgress: (EvalProgress) -> Unit = {},
    ): File {
        check(BuildConfig.DEBUG) { "The eval runner must never run in a release build." }
        val appContext = context.applicationContext
        val modelFileManager = ModelFileManager(appContext)
        check(modelFileManager.isModelReady()) { "The on-device model isn't downloaded yet." }

        val casesA = if ("A" in evals) readCases(appContext, "eval/evalA_criteria_only.jsonl") else emptyList()
        val casesB = if ("B" in evals) readCases(appContext, "eval/evalB_name_given.jsonl") else emptyList()
        val runId = "stage1-eval${evals.sorted().joinToString("")}-${System.currentTimeMillis()}"
        val seed = System.currentTimeMillis()
        // One shuffled order across both evals so heat/time effects aren't confounded with eval type.
        val queue = (casesA.map { "A" to it } + casesB.map { "B" to it }).shuffled(Random(seed))
        val outputDirectory = File(appContext.getExternalFilesDir(null), "eval/$runId").apply { mkdirs() }
        val resultsFile = File(outputDirectory, "$runId.jsonl")
        val metaFile = File(outputDirectory, "run_meta.json")

        val meta = JSONObject()
            .put("run_id", runId)
            .put("stage", 1)
            .put("label", "prompt=V2")
            .put("passes_per_case", 1)
            .put("shuffle_seed", seed)
            .put("started_at_ms", System.currentTimeMillis())
            .put("app_commit", BuildConfig.GIT_DESCRIBE)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android_version", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put(
                "gemma_model",
                JSONObject()
                    .put("file", modelFileManager.modelFile.name)
                    .put("size_bytes", modelFileManager.modelFile.length())
                    .put(
                        "sha256",
                        File(modelFileManager.modelFile.parentFile, "${modelFileManager.modelFile.name}.sha256")
                            .takeIf { it.isFile }?.readText()?.trim(),
                    ),
            )
            .put(
                "sampling",
                JSONObject()
                    .put(
                        "call1_card_list",
                        JSONObject().put("top_k", GemmaConversationResponder.CARD_TOP_K)
                            .put("top_p", GemmaConversationResponder.CARD_TOP_P)
                            .put("temperature", GemmaConversationResponder.CARD_TEMPERATURE),
                    )
                    .put(
                        "call2_detail",
                        JSONObject().put("top_k", GemmaConversationResponder.DETAIL_TOP_K)
                            .put("top_p", GemmaConversationResponder.DETAIL_TOP_P)
                            .put("temperature", GemmaConversationResponder.DETAIL_TEMPERATURE),
                    ),
            )
            .put(
                "prompt_sha256",
                JSONObject()
                    .put("chat_search_instruction.txt", assetSha256(appContext, "prompts/chat_search_instruction.txt"))
                    .put("wine_detail_instruction.txt", assetSha256(appContext, "prompts/wine_detail_instruction.txt")),
            )
            .put("cases", JSONObject().put("A", casesA.size).put("B", casesB.size))
            .put("evalB_sha256", if ("B" in evals) assetSha256(appContext, "eval/evalB_name_given.jsonl") else JSONObject.NULL)
            .put("evalA_sha256", if ("A" in evals) assetSha256(appContext, "eval/evalA_criteria_only.jsonl") else JSONObject.NULL)
            .put("logprobs", "not_available_in_stage_1")
        val batches = JSONArray()
        meta.put("batches", batches)
        meta.put("status", "running")
        metaFile.writeText(meta.toString(2))

        var completed = 0
        var payloadTextMismatchesA = 0
        var knownFactsMismatchesB = 0
        var batchStartedAt = SystemClock.elapsedRealtime()
        val runStartedAt = batchStartedAt
        Log.i(LOG_TAG, "Starting $runId: ${casesA.size} A + ${casesB.size} B -> ${resultsFile.absolutePath}")

        fun writeMeta(status: String) {
            meta.put("status", status)
            meta.put("completed_cases", completed)
            meta.put("elapsed_ms", SystemClock.elapsedRealtime() - runStartedAt)
            meta.put("eval_a_payload_text_mismatches", payloadTextMismatchesA)
            meta.put("eval_b_known_facts_mismatches", knownFactsMismatchesB)
            metaFile.writeText(meta.toString(2))
        }

        try {
            GemmaConversationResponder(appContext, modelFileManager.modelFile).use { responder ->
                responder.prepare()
                for ((evalName, case) in queue) {
                    while (controller.paused) delay(300)
                    val startedAt = SystemClock.elapsedRealtime()
                    val line = JSONObject()
                        .put("run_id", runId).put("stage", 1).put("sample", 1)
                        .put("eval", evalName).put("case_id", case.getString("case_id"))
                    val cardsShown = JSONArray()
                    var error: String? = null
                    GemmaEvalTrace.begin()
                    try {
                        if (evalName == "A") {
                            copyThrough(case, line, "kind", "n_criteria", "payload_dict", "payload_text", "valid_answers_in_db", "source_row_id", "high_confidence_traits")
                            val preferences = checkNotNull(WinePreferences.fromJsonOrNull(case.getJSONObject("payload_dict").toString())) {
                                "payload_dict could not be turned into preferences"
                            }
                            val message = responder.synthesizeCards(preferences) {}
                            message.suggestions.forEach { cardsShown.put(it.toListCardJson()) }
                        } else {
                            copyThrough(case, line, "kind", "known_facts_sent_to_gemma", "preset_by_user", "gemma_should_fill", "hidden_truth", "high_confidence_traits", "expected")
                            val enriched = responder.enrichWineDetails(case.getJSONObject("known_facts_sent_to_gemma").toCard())
                            cardsShown.put(enriched.toDetailJson())
                        }
                    } catch (cancelled: CancellationException) {
                        GemmaEvalTrace.end()
                        throw cancelled
                    } catch (failure: Throwable) {
                        error = "${failure.javaClass.simpleName}: ${failure.message}"
                    }
                    val trace = GemmaEvalTrace.end()
                    val traceJson = trace?.toJson()
                    line.put("request_sent", traceJson?.opt("request_sent") ?: JSONObject.NULL)
                    line.put("raw_response", traceJson?.opt("raw_response") ?: JSONObject.NULL)
                    line.put("cards_shown", cardsShown)
                    line.put("app_actions", trace?.repairs?.toAppActions() ?: JSONArray())
                    line.put("parse_ok", traceJson?.optBoolean("parse_ok", false) ?: false)
                    line.put("logprobs", JSONObject.NULL)
                    line.put("ms", SystemClock.elapsedRealtime() - startedAt)
                    line.put("error", error ?: JSONObject.NULL)

                    // Does what the real builder sent match the reference the test author wrote?
                    val sentMessage = traceJson?.optJSONObject("request_sent")?.optString("user_message").orEmpty()
                    if (evalName == "A") {
                        val matches = sentMessage.removePrefix("Resolved preferences: ") == case.getString("payload_text")
                        line.put("payload_text_equal", matches)
                        if (!matches) payloadTextMismatchesA++
                    } else {
                        val diff = knownFactsDifference(
                            reference = case.getJSONObject("known_facts_sent_to_gemma"),
                            sentMessage = sentMessage,
                        )
                        line.put("known_facts_difference", diff ?: JSONObject.NULL)
                        if (diff != null) knownFactsMismatchesB++
                    }

                    resultsFile.appendText(line.toString() + "\n")
                    completed++
                    onProgress(EvalProgress(completed, queue.size, case.getString("case_id")))
                    Log.i(LOG_TAG, "[$completed/${queue.size}] $evalName ${case.getString("case_id")}")

                    if (completed % BATCH_SIZE == 0 || completed == queue.size) {
                        batches.put(
                            JSONObject().put("batch", batches.length() + 1).put("cases_done", completed)
                                .put("batch_ms", SystemClock.elapsedRealtime() - batchStartedAt)
                                .put("thermal_status", thermalStatus(appContext))
                                .put("battery_temp_c", batteryTempC(appContext)),
                        )
                        batchStartedAt = SystemClock.elapsedRealtime()
                        writeMeta("running")
                    }
                }
            }
            writeMeta("finished")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { writeMeta("cancelled") }
            throw cancelled
        } catch (failure: Throwable) {
            writeMeta("failed: ${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        }
        Log.i(LOG_TAG, "Finished $runId in ${SystemClock.elapsedRealtime() - runStartedAt}ms")
        return resultsFile
    }

    private fun readCases(context: Context, path: String): List<JSONObject> =
        context.assets.open(path).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
        }

    private fun copyThrough(from: JSONObject, to: JSONObject, vararg keys: String) {
        keys.forEach { key -> if (from.has(key)) to.put(key, from.get(key)) }
    }

    /** The card Call 1 leaves behind for the detail screen, rebuilt from the known facts. */
    private fun JSONObject.toCard(): WineSuggestion {
        fun field(key: String) = optString(key, "").ifBlank { "Unknown" }
        return WineSuggestion(
            name = getString("name"),
            winery = field("winery"),
            wineType = field("wine_type"),
            country = field("country"),
            province = field("province"),
            variety = field("variety"),
            sweetness = field("sweetness"),
            body = field("body"),
            tannin = field("tannin"),
            acidity = field("acidity"),
            flavorNotes = field("flavor_notes"),
            suggestedPairing = field("suggested_pairing"),
            summary = field("summary"),
        )
    }

    /** What a card in the Find/Chat result list displays. */
    private fun WineSuggestion.toListCardJson() = JSONObject()
        .put("name", name).put("country", country).put("province", province).put("variety", variety)

    /** What the detail screen displays. */
    private fun WineSuggestion.toDetailJson() = JSONObject()
        .put("name", name).put("winery", winery).put("wine_type", wineType)
        .put("country", country).put("province", province).put("variety", variety)
        .put("sweetness", sweetness).put("body", body).put("tannin", tannin).put("acidity", acidity)
        .put("flavor_notes", flavorNotes).put("suggested_pairing", suggestedPairing).put("summary", summary)

    /** Names the app steps the brief asked to see (country alias, sweetness clean-up) by their brief names. */
    private fun JSONArray.toAppActions(): JSONArray {
        val actions = JSONArray()
        for (index in 0 until length()) {
            val repair = getJSONObject(index)
            val kind = repair.getString("kind")
            val field = repair.optString("field")
            val renamed = when {
                kind == "label_rewritten" && field == "country" -> "country_alias_converted"
                kind == "label_rewritten" && field == "sweetness" -> "sweetness_cleaned"
                kind == "label_unmapped_set_unknown" && field == "sweetness" -> "sweetness_cleaned"
                else -> kind
            }
            actions.put(JSONObject().put("kind", renamed).put("field", field).put("detail", repair.optString("detail")).put("phase", repair.optString("phase")))
        }
        return actions
    }

    /** Null when the real "Known wine data" JSON carries the same facts, in the same key order, as the reference. */
    private fun knownFactsDifference(reference: JSONObject, sentMessage: String): String? {
        val sent = runCatching { JSONObject(sentMessage.removePrefix("Known wine data: ")) }.getOrNull()
            ?: return "could not read the request actually sent"
        val referenceKeys = reference.keys().asSequence().toList()
        val sentKeys = sent.keys().asSequence().toList()
        val sameContent = referenceKeys.toSet() == sentKeys.toSet() &&
            referenceKeys.all { reference.opt(it).toString() == sent.opt(it).toString() }
        return when {
            !sameContent -> "different facts: reference=$reference, sent=$sent"
            referenceKeys != sentKeys -> "same facts, different key order: reference=$referenceKeys, sent=$sentKeys"
            else -> null
        }
    }

    private fun assetSha256(context: Context, path: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(context.assets.open(path).use { it.readBytes() })
            .joinToString("") { "%02x".format(it) }

    private fun thermalStatus(context: Context): String? = if (Build.VERSION.SDK_INT >= 29) {
        when ((context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    } else {
        null
    }

    private fun batteryTempC(context: Context): Double? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
}
