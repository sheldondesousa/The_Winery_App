package com.sheldondesousa.uncork.eval

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.sheldondesousa.uncork.model.GemmaConversationResponder
import com.sheldondesousa.uncork.model.ModelFileManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val LOG_TAG = "GemmaHandoverEval"
private const val RUNS_PER_CASE = 3

/**
 * Debug-only. Runs the 40 Kotlin<->Gemma handover cases (assets/eval/handover_cases.json) 3 times
 * each through the real [GemmaConversationResponder.replyTo], one fresh conversation state per run,
 * and writes one JSON line per run: setup replies, the verbatim reply to the test input, and the
 * routing path the responder reported. Scoring happens off-device.
 */
internal object GemmaHandoverEvalRunner {
    suspend fun run(context: Context, onProgress: (EvalProgress) -> Unit = {}): File {
        val appContext = context.applicationContext
        val cases = JSONArray(appContext.assets.open("eval/handover_cases.json").bufferedReader().use { it.readText() })
        val modelFileManager = ModelFileManager(appContext)
        check(modelFileManager.isModelReady()) { "The on-device model isn't downloaded yet." }

        val outputDirectory = File(appContext.getExternalFilesDir(null), "eval").apply { mkdirs() }
        val outputFile = File(outputDirectory, "handover-results-${System.currentTimeMillis()}.jsonl")
        val total = cases.length() * RUNS_PER_CASE
        var completed = 0

        GemmaConversationResponder(appContext, modelFileManager.modelFile).use { responder ->
            responder.prepare()
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                for (run in 1..RUNS_PER_CASE) {
                    val startedAt = SystemClock.elapsedRealtime()
                    responder.resetConversationState()
                    val entryInput = if (case.getString("mode") == "find") "find a wine" else "curious"
                    val entry = responder.replyTo(entryInput).text
                    val entryRoute = responder.lastRoute
                    val setup = JSONArray()
                    val priors = case.getJSONArray("prior")
                    for (p in 0 until priors.length()) {
                        val input = priors.getString(p)
                        val reply = responder.replyTo(input).text
                        setup.put(JSONObject().put("input", input).put("reply", reply).put("route", responder.lastRoute))
                    }
                    val input = case.getString("input")
                    val reply = responder.replyTo(input)
                    val line = JSONObject()
                        .put("id", case.getString("id"))
                        .put("run", run)
                        .put("mode", case.getString("mode"))
                        .put("entry_input", entryInput)
                        .put("entry_reply", entry)
                        .put("entry_route", entryRoute)
                        .put("setup", setup)
                        .put("input", input)
                        .put("reply", reply.text)
                        .put("route", responder.lastRoute)
                        .put("ms", SystemClock.elapsedRealtime() - startedAt)
                    outputFile.appendText(line.toString() + "\n")
                    completed++
                    Log.i(LOG_TAG, "[$completed/$total] ${case.getString("id")} run $run route=${responder.lastRoute}")
                    onProgress(EvalProgress(completed, total, case.getString("id")))
                }
            }
        }
        return outputFile
    }
}
