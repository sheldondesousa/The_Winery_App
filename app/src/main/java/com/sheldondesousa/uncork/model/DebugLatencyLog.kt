package com.sheldondesousa.uncork.model

import android.os.SystemClock

/**
 * Debug-only, in-memory timing breakdown for a single Chat turn. Collects labeled stage
 * durations as the request passes through GemmaConversationResponder / KaggleConversationResponder,
 * then the UI drains and displays them alongside the total round-trip time. Never persisted,
 * never shown in release builds, never fed back into model input.
 */
internal object DebugLatencyLog {
    private val entries = mutableListOf<Pair<String, Long>>()

    @Synchronized
    fun record(label: String, elapsedMs: Long) {
        entries += label to elapsedMs
    }

    @Synchronized
    fun drain(): List<Pair<String, Long>> {
        val copy = entries.toList()
        entries.clear()
        return copy
    }

    suspend fun <T> timed(label: String, block: suspend () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        val result = block()
        record(label, SystemClock.elapsedRealtime() - startedAt)
        return result
    }
}
