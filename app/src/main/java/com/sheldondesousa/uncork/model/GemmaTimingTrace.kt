package com.sheldondesousa.uncork.model

import android.os.SystemClock
import com.sheldondesousa.uncork.BuildConfig
import java.io.File
import java.util.UUID

/** Metadata only: never stores prompts, preferences, or generated wine descriptions. */
internal class GemmaTimingTrace(private val filesDirectory: File) {
    private val id = UUID.randomUUID().toString()
    private val startedAt = System.currentTimeMillis()
    private val entries = mutableListOf<String>()

    fun duration(stage: String, elapsedMs: Long) = detail(stage + "_ms", elapsedMs.toString())

    fun detail(key: String, value: String) {
        if (BuildConfig.DEBUG) entries += "$key=$value"
    }

    // Inline so suspend operations retain the caller's coroutine context and cancellation.
    suspend inline fun <T> measure(stage: String, block: () -> T): T {
        val started = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            duration(stage, SystemClock.elapsedRealtime() - started)
        }
    }

    /** Called on Dispatchers.IO, after the measured work; keep at most two bounded files. */
    fun save() {
        if (!BuildConfig.DEBUG) return
        val line = "GemmaTiming request=$id startedAtEpochMs=$startedAt ${entries.joinToString(" ")}"
        logFlow(line)
        runCatching {
            synchronized(fileLock) {
                val directory = File(filesDirectory, "diagnostics").apply { mkdirs() }
                val current = File(directory, "gemma-timings.log")
                if (current.length() + line.toByteArray(Charsets.UTF_8).size + 1 > MAX_BYTES) {
                    val previous = File(directory, "gemma-timings.previous.log")
                    if (previous.exists()) check(previous.delete())
                    check(current.renameTo(previous))
                }
                current.appendText(line + "\n", Charsets.UTF_8)
            }
        }.onFailure { logFlow("Gemma timing file write failed (${it.javaClass.simpleName})") }
    }

    private companion object {
        val fileLock = Any()
        const val MAX_BYTES = 256 * 1024
    }
}
