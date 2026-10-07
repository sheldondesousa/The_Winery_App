package com.sheldondesousa.uncork.eval

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.sheldondesousa.uncork.model.AskExtras
import com.sheldondesousa.uncork.model.GemmaConversationResponder
import com.sheldondesousa.uncork.model.ModelFileManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val LOG_TAG = "GemmaRagEval"

/**
 * Debug-only. Runs the conversations in assets/eval/uncork_rag_test_set_v1.json through the real open (curious) chat,
 * once with RAG off and once with RAG on, and writes one JSON line per conversation and mode in the shape the judge file
 * asks for: each turn's user message, Gemma's reply and the exact text the retrieval added to the prompt. Scoring happens
 * off-device.
 */
internal object GemmaRagEvalRunner {
    /** Remembers what the wrapped extras added to the prompt, so it can be saved next to the reply. */
    private class RecordingExtras(private val inner: AskExtras) : AskExtras {
        val chunks = mutableListOf<String>()
        override suspend fun forQuestion(query: String): String? = inner.forQuestion(query)?.also { chunks += it.split("\n\n").filter { c -> c.isNotBlank() } }
        override suspend fun wineryList(query: String): String? = inner.wineryList(query)?.also { chunks += it }
        override fun reset() = inner.reset()
    }

    /**
     * Starts the bottle conversation (the Ask screen's wine_discussion_instruction prompt) on [responder] for a blank test
     * bottle. When [ragOn], the bottle's own lookups must be passed through [record] so what they add can be saved.
     */
    internal fun interface BottleFactory {
        fun start(responder: GemmaConversationResponder, ragOn: Boolean, record: (AskExtras) -> AskExtras): GemmaConversationResponder.WineDiscussion
    }

    suspend fun run(
        context: Context,
        ragExtras: () -> AskExtras,
        onProgress: (EvalProgress) -> Unit = {},
        bottle: BottleFactory? = null,
    ): File {
        val appContext = context.applicationContext
        val set = JSONObject(appContext.assets.open("eval/uncork_rag_test_set_v1.json").bufferedReader().use { it.readText() })
        val conversations = set.getJSONArray("conversations")
        val modelFileManager = ModelFileManager(appContext)
        check(modelFileManager.isModelReady()) { "The on-device model isn't downloaded yet." }

        val outputDirectory = File(appContext.getExternalFilesDir(null), "eval").apply { mkdirs() }
        val outputFile = File(outputDirectory, "rag-${if (bottle != null) "bottle-" else ""}results-${System.currentTimeMillis()}.jsonl")
        File(outputDirectory, outputFile.nameWithoutExtension + "-meta.json").writeText(
            JSONObject().put("test_set", set.getJSONObject("meta").getString("name")).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("prompt", if (bottle != null) "wine_discussion_instruction.txt (bottle)" else "curious_chat_instruction.txt (open chat)")
                .put("android", Build.VERSION.RELEASE).put("model_file", modelFileManager.modelFile.name)
                .put("model_bytes", modelFileManager.modelFile.length()).toString(2),
        )
        val total = conversations.length() * 2
        var completed = 0

        GemmaConversationResponder(appContext, modelFileManager.modelFile).use { responder ->
            responder.prepare()
            for (i in 0 until conversations.length()) {
                val case = conversations.getJSONObject(i)
                val userTurns = case.getJSONArray("turns")
                for (ragOn in listOf(false, true)) {
                    val startedAt = SystemClock.elapsedRealtime()
                    var recorder: RecordingExtras? = null
                    // A fresh conversation for every run: the bottle chat, or the open chat after "curious".
                    val discussion = bottle?.start(responder, ragOn) { inner -> RecordingExtras(inner).also { recorder = it } }
                    if (bottle == null && ragOn) recorder = RecordingExtras(ragExtras())
                    val entry = if (discussion != null) "" else {
                        responder.attachCuriousExtras(recorder)
                        responder.resetConversationState()
                        responder.replyTo("curious").text
                    }
                    val turns = JSONArray()
                    try {
                        for (t in 0 until userTurns.length()) {
                            val user = userTurns.getString(t)
                            recorder?.chunks?.clear()
                            val reply = (discussion?.replyTo(user) ?: responder.replyTo(user)).text
                            turns.put(
                                JSONObject().put("user", user).put("reply", reply).put("route", responder.lastRoute)
                                    .put("retrieved_chunks", JSONArray(recorder?.chunks.orEmpty())),
                            )
                        }
                    } finally {
                        discussion?.close()
                    }
                    outputFile.appendText(
                        JSONObject().put("id", case.getString("id")).put("rag_enabled", ragOn).put("entry_reply", entry)
                            .put("turns", turns).put("ms", SystemClock.elapsedRealtime() - startedAt).toString() + "\n",
                    )
                    completed++
                    Log.i(LOG_TAG, "[$completed/$total] ${case.getString("id")} rag=$ragOn")
                    onProgress(EvalProgress(completed, total, "${case.getString("id")} ${if (ragOn) "RAG on" else "RAG off"}"))
                }
            }
        }
        return outputFile
    }
}
