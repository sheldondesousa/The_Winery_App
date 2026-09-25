package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.BuildConfig
import java.io.File
import java.util.UUID

/** Metadata-only timing for loading the pending fields on a Stage Show page. */
internal class StageShowTimingTrace(private val filesDirectory: File) {
    private val id = UUID.randomUUID().toString()
    private val startedAt = System.currentTimeMillis()
    private val entries = mutableListOf<String>()

    fun duration(stage: String, elapsedMs: Long) = detail(stage + "_ms", elapsedMs.toString())

    fun detail(key: String, value: String) {
        if (BuildConfig.DEBUG) entries += "$key=$value"
    }

    fun save() {
        if (!BuildConfig.DEBUG) return
        val line = "StageShowTiming request=$id startedAtEpochMs=$startedAt ${entries.joinToString(" ")}"
        logFlow(line)
        runCatching {
            synchronized(fileLock) {
                val directory = File(filesDirectory, "diagnostics").apply { mkdirs() }
                val current = File(directory, "stage-show-timings.log")
                if (current.length() + line.toByteArray(Charsets.UTF_8).size + 1 > MAX_BYTES) {
                    val previous = File(directory, "stage-show-timings.previous.log")
                    if (previous.exists()) check(previous.delete())
                    check(current.renameTo(previous))
                }
                current.appendText(line + "\n", Charsets.UTF_8)
            }
        }.onFailure { logFlow("Stage Show timing file write failed (${it.javaClass.simpleName})") }
    }

    private companion object {
        val fileLock = Any()
        const val MAX_BYTES = 256 * 1024
    }
}
