package com.sheldondesousa.uncork.eval

import android.content.Context
import android.os.SystemClock
import com.sheldondesousa.uncork.BuildConfig
import com.sheldondesousa.uncork.model.AskExtras
import com.sheldondesousa.uncork.model.AskLookup
import com.sheldondesousa.uncork.model.GemmaConversationResponder
import com.sheldondesousa.uncork.model.ModelFileManager
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import org.json.JSONObject
import java.io.File

internal data class EvalProgress(val completed: Int, val total: Int, val lastCaseId: String)

/** Exercises the production bottle ASK path. Answer keys are output metadata only, never model input. */
internal object BottleAskEvalRunner {
    internal fun interface BottleFactory {
        fun start(responder: GemmaConversationResponder, wine: WineSuggestion, record: (AskExtras) -> AskExtras, recordFacts: (String) -> Unit): GemmaConversationResponder.WineDiscussion
    }

    private class RecordingExtras(private val inner: AskExtras) : AskExtras {
        var lookup: AskLookup? = null
        override suspend fun lookup(query: String): AskLookup = inner.lookup(query).also { lookup = it }
        override suspend fun forQuestion(query: String): String? = inner.forQuestion(query)
        override suspend fun wineryList(query: String): String? = inner.wineryList(query)
        override fun reset() = inner.reset()
    }

    suspend fun run(
        context: Context,
        bottle: BottleFactory,
        assetName: String = "bottle_ask_v3.json",
        runIndex: Int = 1,
        outputDirectory: File? = null,
        onProgress: (EvalProgress) -> Unit,
    ): File {
        check(BuildConfig.DEBUG) { "Evaluation is available only in debug builds." }
        require(assetName in setOf("bottle_ask_v3.json", "bottle_ask_v4.json", "bottle_ask_v5.json", "bottle_ask_v6.json"))
        val datasetBytes = context.assets.open("eval/$assetName").use { it.readBytes() }
        val set = JSONObject(datasetBytes.toString(Charsets.UTF_8))
        val cases = set.getJSONArray("cases")
        val manager = ModelFileManager(context.applicationContext)
        check(manager.isModelReady()) { "The on-device model isn't downloaded yet." }
        val directory = (outputDirectory ?: File(context.getExternalFilesDir(null), "eval")).apply { mkdirs() }
        val prefix = assetName.removeSuffix(".json").replace('_', '-')
        val output = File(directory, "$prefix-run$runIndex-${System.currentTimeMillis()}.jsonl")
        val promptHash = sha256(context.assets.open("prompts/wine_discussion_instruction.txt").use { it.readBytes() })
        GemmaConversationResponder(context.applicationContext, manager.modelFile).use { responder ->
            responder.prepare()
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val selected = case.getJSONObject("bottle")
                val wine = WineSuggestion(name = selected.getString("name"), variety = selected.getString("variety"),
                    country = selected.getString("country"), province = selected.getString("province"))
                var recorder: RecordingExtras? = null
                var bottleFacts = ""
                val discussion = bottle.start(responder, wine,
                    { RecordingExtras(it).also { value -> recorder = value } },
                    { bottleFacts = it })
                try {
                    // ASK warms its selected-bottle context before the first question too.
                    discussion.warmUp()
                    val start = SystemClock.elapsedRealtime()
                    var firstWordMs: Long? = null
                    val reply = discussion.replyToUpdates(case.getString("question")) { update ->
                        if (firstWordMs == null && update.isGemmaConversationOutput && update.text.any { it.isLetterOrDigit() }) {
                            firstWordMs = SystemClock.elapsedRealtime() - start
                        }
                    }.text
                    val route = responder.lastRoute.orEmpty()
                    val audit = responder.lastWineReplyAudit
                    val expectedRoute = case.getString("expected_route")
                    val routeMatches = route == expectedRoute
                    // The route only says where a question was sent. What the user reads can still be swapped by the app
                    // afterwards (the reply check's refusal, a winery list), so pass/fail also checks who wrote the shown text.
                    val outputSource = audit?.outputSource ?: if (route == "KOTLIN_REFUSAL") "KOTLIN_LOOKUP_REFUSAL" else "KOTLIN_DIRECT"
                    val shownByGemma = outputSource == "GEMMA" || outputSource == "GEMMA_TIDIED"
                    val sourceMatches = if (expectedRoute == "WINE_DISCUSSION") shownByGemma else !shownByGemma
                    val exact = case.getString("grading") == "exact"
                    val result = JSONObject(case.toString())
                        .put("dataset_name", set.getJSONObject("meta").getString("name"))
                        .put("dataset_version", set.getJSONObject("meta").getInt("version"))
                        .put("dataset_sha256", sha256(datasetBytes))
                        .put("run_index", runIndex)
                        .put("actual_answer", reply).put("actual_route", route)
                        .put("route_matches", routeMatches)
                        .put("shown_by", if (shownByGemma) "GEMMA" else "KOTLIN")
                        .put("source_matches", sourceMatches)
                        .put("status", if (!routeMatches || !sourceMatches) "FAIL" else if (!exact) "REVIEW" else if (reply == case.getString("expected_answer")) "PASS" else "FAIL")
                        .put("bottle_facts", bottleFacts)
                        .put("retrieved_context", recorder?.lookup?.context ?: JSONObject.NULL)
                        .put("kotlin_refusal", recorder?.lookup?.refusal ?: JSONObject.NULL)
                        .put("lookup_outcome", recorder?.lookup?.outcome?.name ?: "NOT_LOOKED_UP")
                        .put("app_version", BuildConfig.VERSION_NAME)
                        .put("app_version_code", BuildConfig.VERSION_CODE)
                        .put("prompt_sha256", promptHash)
                        .put("first_word_ms", firstWordMs ?: JSONObject.NULL)
                        .put("gemma_initial_answer", audit?.initialModelText ?: JSONObject.NULL)
                        .put("gemma_answer", audit?.lastModelText ?: JSONObject.NULL)
                        .put("output_source", outputSource)
                        .put("reply_check_retried", audit?.retryAttempted ?: false)
                        .put("reply_check_context", audit?.checkContext ?: JSONObject.NULL)
                        .put("elapsed_ms", SystemClock.elapsedRealtime() - start)
                    output.appendText(result.toString() + "\n")
                } finally {
                    discussion.close()
                }
                onProgress(EvalProgress(index + 1, cases.length(), case.getString("id")))
            }
        }
        return output
    }

    /** Three manual, data-preserving passes through the same production ASK path. */
    suspend fun runBroadBatch(context: Context, bottle: BottleFactory, onProgress: (EvalProgress) -> Unit): File {
        check(BuildConfig.DEBUG)
        val directory = File(context.getExternalFilesDir(null), "eval/bottle-ask-v4-batch-${System.currentTimeMillis()}").apply { mkdirs() }
        val combined = File(directory, "combined.jsonl")
        val manifest = File(directory, "manifest.json")
        val completedFiles = org.json.JSONArray()
        fun save(status: String, error: String? = null) {
            manifest.writeText(JSONObject().put("status", status).put("planned_runs", 3)
                .put("completed_runs", completedFiles.length()).put("files", completedFiles)
                .put("error", error ?: JSONObject.NULL).toString(2))
        }
        save("RUNNING")
        try {
            repeat(3) { pass ->
                val result = run(context, bottle, "bottle_ask_v4.json", pass + 1, directory) { progress ->
                    onProgress(EvalProgress(pass * 100 + progress.completed, 300, "${progress.lastCaseId} (run ${pass + 1}/3)"))
                }
                combined.appendText(result.readText())
                completedFiles.put(result.name)
                save(if (pass == 2) "COMPLETE" else "RUNNING")
            }
        } catch (error: Throwable) {
            save("INCOMPLETE", error.message ?: error.javaClass.simpleName)
            throw error
        }
        return combined
    }

    /**
     * One pass over a held-out set: v5 (300 questions in equal thirds) or v6 (300 grape-knowledge questions, half on the
     * extracted table). v4 gave near-identical output on repeat runs, so one pass is enough.
     */
    suspend fun runOnce(context: Context, bottle: BottleFactory, assetName: String, onProgress: (EvalProgress) -> Unit): File {
        check(BuildConfig.DEBUG) { "Evaluation is available only in debug builds." }
        require(assetName in setOf("bottle_ask_v5.json", "bottle_ask_v6.json"))
        val directory = File(context.getExternalFilesDir(null), "eval/${assetName.removeSuffix(".json").replace('_', '-')}-${System.currentTimeMillis()}").apply { mkdirs() }
        return run(context, bottle, assetName, 1, directory, onProgress)
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
