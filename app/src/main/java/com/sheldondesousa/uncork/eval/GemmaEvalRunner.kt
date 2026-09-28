package com.sheldondesousa.uncork.eval

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.sheldondesousa.uncork.model.GemmaConversationResponder
import com.sheldondesousa.uncork.model.ModelFileManager
import java.io.File

/** Progress after finishing one (test case, run) generation, for a caller to show e.g. "42/150". */
internal data class EvalProgress(val completed: Int, val total: Int, val lastCaseId: String)

private const val LOG_TAG = "GemmaEval"
private const val REPEATS_PER_CASE = 3
// Only used when the test file itself has no meta.prompt_version_under_test.
private const val FALLBACK_PROMPT_VERSION = "v1"

/**
 * Runs every conversation in the bundled eval test set through the real chat inference path
 * ([GemmaConversationResponder.replyTo], the same call [com.sheldondesousa.uncork.ui.conversation.ConversationRoute]
 * uses for a "curious" free-chat reply) three times each, writes the results to a JSON file, and
 * returns that file. Debug-only: never invoked from release UI.
 *
 * One [GemmaConversationResponder] — and so one loaded model engine — is reused across every
 * conversation; only the [GemmaConversationResponder.resetConversationState] in between resets
 * history, so a 150-generation run doesn't pay engine cold-start 150 times. Each conversation
 * still gets its own fresh `curious` mode selection and multi-turn history, exactly as a real
 * chat session would.
 */
internal object GemmaEvalRunner {
    suspend fun run(
        context: Context,
        onProgress: (EvalProgress) -> Unit = {},
    ): File {
        val appContext = context.applicationContext
        val testSet = loadEvalTestSet(appContext)
        val testCases = testSet.cases
        val modelFileManager = ModelFileManager(appContext)
        check(modelFileManager.isModelReady()) { "The on-device model isn't downloaded yet." }

        val total = testCases.size * REPEATS_PER_CASE
        var completed = 0
        val results = mutableListOf<EvalResult>()
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(LOG_TAG, "Starting eval: ${testCases.size} conversations x $REPEATS_PER_CASE runs = $total generations")

        GemmaConversationResponder(appContext, modelFileManager.modelFile).use { responder ->
            responder.prepare()
            for (testCase in testCases) {
                for (run in 1..REPEATS_PER_CASE) {
                    val caseStartedAt = SystemClock.elapsedRealtime()
                    responder.resetConversationState()
                    // Enter "curious" free-chat mode the same way a real user's first tap would;
                    // this opening reply isn't part of the test case and isn't recorded.
                    responder.replyTo("curious")
                    val replies = testCase.turns.map { turn -> responder.replyTo(turn).text }
                    results += EvalResult(id = testCase.id, run = run, replies = replies)
                    completed++
                    Log.i(
                        LOG_TAG,
                        "[$completed/$total] ${testCase.id} run $run done in " +
                            "${SystemClock.elapsedRealtime() - caseStartedAt}ms",
                    )
                    onProgress(EvalProgress(completed, total, testCase.id))
                }
            }
        }

        Log.i(LOG_TAG, "Eval finished in ${SystemClock.elapsedRealtime() - startedAt}ms")
        val modelName = ModelFileManager.MODEL_FILE_NAME.removeSuffix(".litertlm")
        val promptVersion = testSet.promptVersion ?: FALLBACK_PROMPT_VERSION
        val json = writeEvalResults(promptVersion = promptVersion, model = modelName, results = results)
        val outputDirectory = File(appContext.getExternalFilesDir(null), "eval").apply { mkdirs() }
        val outputFile = File(outputDirectory, "eval-results-${System.currentTimeMillis()}.json")
        outputFile.writeText(json)
        Log.i(LOG_TAG, "Wrote results to ${outputFile.absolutePath}")
        return outputFile
    }
}
