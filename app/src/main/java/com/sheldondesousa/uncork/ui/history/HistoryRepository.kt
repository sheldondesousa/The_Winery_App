package com.sheldondesousa.uncork.ui.history

import android.content.Context
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val id: Long,
    val createdAtEpochMillis: Long,
    val suggestion: WineSuggestion,
    val request: String,
)

class HistoryRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): List<HistoryEntry> {
        val stored = preferences.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toHistoryEntryOrNull()?.let(::add)
                }
            }.sortedByDescending(HistoryEntry::createdAtEpochMillis)
        }.getOrDefault(emptyList())
    }

    fun record(suggestion: WineSuggestion, userRequest: String): List<HistoryEntry> {
        val updated = buildList {
            add(
                HistoryEntry(
                    id = System.nanoTime(),
                    createdAtEpochMillis = System.currentTimeMillis(),
                    suggestion = suggestion,
                    request = userRequest.toConciseRequest(),
                ),
            )
            addAll(load())
        }.take(MAX_HISTORY_ENTRIES)
        save(updated)
        return updated
    }

    private fun save(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun HistoryEntry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAtEpochMillis)
        put("request", request)
        put("suggestion", suggestion.toJson())
    }

    private fun WineSuggestion.toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("region", region)
        put("winery", winery)
        put("variety", variety)
        put("body", body)
        put("tannin", tannin)
        put("acidity", acidity)
        put("flavorNotes", flavorNotes)
        put("sourceRating", sourceRating)
        put("confidencePercent", confidencePercent ?: JSONObject.NULL)
        put("favoriteRating", favoriteRating ?: JSONObject.NULL)
        put("isFavorite", isFavorite)
    }

    private fun JSONObject.toHistoryEntryOrNull(): HistoryEntry? = runCatching {
        HistoryEntry(
            id = getLong("id"),
            createdAtEpochMillis = getLong("createdAt"),
            request = optString("request").ifBlank { LEGACY_REQUEST_LABEL },
            suggestion = getJSONObject("suggestion").toSuggestion(),
        )
    }.getOrNull()

    private fun JSONObject.toSuggestion(): WineSuggestion = WineSuggestion(
        name = getString("name"),
        region = getString("region"),
        winery = optString("winery", getString("name")),
        variety = optString("variety", getString("name")),
        body = optString("body", "Unknown"),
        tannin = optString("tannin", "Unknown"),
        acidity = optString("acidity", "Unknown"),
        flavorNotes = optString("flavorNotes", "Unknown"),
        sourceRating = optString("sourceRating", "Unknown"),
        confidencePercent = optIntOrNull("confidencePercent"),
        favoriteRating = optIntOrNull("favoriteRating"),
        isFavorite = optBoolean("isFavorite", false),
    )

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    private fun String.toConciseRequest(): String {
        val words = trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return DEFAULT_REQUEST_LABEL
        val summary = words.take(MAX_REQUEST_WORDS).joinToString(" ")
        return if (words.size > MAX_REQUEST_WORDS) "$summary…" else summary
    }

    private companion object {
        const val PREFERENCES_NAME = "uncork_history"
        const val KEY_ENTRIES = "entries"
        const val MAX_REQUEST_WORDS = 6
        const val MAX_HISTORY_ENTRIES = 500
        const val DEFAULT_REQUEST_LABEL = "Wine suggestion"
        const val LEGACY_REQUEST_LABEL = "Previous wine suggestion"
    }
}
