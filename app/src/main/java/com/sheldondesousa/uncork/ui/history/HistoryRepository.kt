package com.sheldondesousa.uncork.ui.history

import android.content.Context
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val id: Long,
    val createdAtEpochMillis: Long,
    val suggestion: WineSuggestion,
    val requestKeywords: List<String>,
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
                    requestKeywords = RequestKeywordExtractor.extract(userRequest, suggestion),
                ),
            )
            addAll(load())
        }.take(MAX_HISTORY_ENTRIES)
        save(updated)
        return updated
    }

    fun delete(entryIds: Set<Long>): List<HistoryEntry> {
        if (entryIds.isEmpty()) return load()
        val updated = load().filterNot { it.id in entryIds }
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
        put("requestKeywords", JSONArray(requestKeywords))
        put("suggestion", suggestion.toJson())
    }

    private fun WineSuggestion.toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("country", country)
        put("province", province)
        put("wineType", wineType)
        put("winery", winery)
        put("variety", variety)
        put("body", body)
        put("tannin", tannin)
        put("acidity", acidity)
        put("flavorNotes", flavorNotes)
        put("suggestedPairing", suggestedPairing)
        put("summary", summary)
        put("rating", rating ?: JSONObject.NULL)
        put("reviewSummary", reviewSummary)
        put("webSummary", webSummary)
        put("source", source.name)
        put("requestContext", requestContext ?: JSONObject.NULL)
        put("favoriteRating", favoriteRating ?: JSONObject.NULL)
        put("isFavorite", isFavorite)
    }

    private fun JSONObject.toHistoryEntryOrNull(): HistoryEntry? = runCatching {
        HistoryEntry(
            id = getLong("id"),
            createdAtEpochMillis = getLong("createdAt"),
            requestKeywords = optJSONArray("requestKeywords")?.toStringList()
                ?.takeIf(List<String>::isNotEmpty)
                ?: listOf(LEGACY_REQUEST_LABEL),
            suggestion = getJSONObject("suggestion").toSuggestion(),
        )
    }.getOrNull()

    private fun JSONObject.toSuggestion(): WineSuggestion = WineSuggestion(
        name = getString("name"),
        country = optString("country", "Unknown"),
        province = provinceValue(),
        wineType = optString("wineType", "Unknown"),
        winery = optString("winery", getString("name")),
        variety = optString("variety", getString("name")),
        body = optString("body", "Unknown"),
        tannin = optString("tannin", "Unknown"),
        acidity = optString("acidity", "Unknown"),
        flavorNotes = optString("flavorNotes", "Unknown"),
        suggestedPairing = optString("suggestedPairing", "Unknown"),
        summary = optString("summary", "Unknown"),
        rating = optIntOrNull("rating"),
        reviewSummary = optString("reviewSummary", "Unknown"),
        webSummary = optString("webSummary", "Unknown"),
        source = suggestionSource(),
        requestContext = optNullableString("requestContext"),
        favoriteRating = optIntOrNull("favoriteRating"),
        isFavorite = optBoolean("isFavorite", false),
    )

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun JSONObject.suggestionSource(): WineSuggestionSource =
        runCatching { WineSuggestionSource.valueOf(optString("source")) }.getOrElse {
            when {
                optIntOrNull("rating") != null -> WineSuggestionSource.KAGGLE
                !optString("reviewSummary", "Unknown").equals("Unknown", true) ->
                    WineSuggestionSource.KAGGLE
                !optString("webSummary", "Unknown").equals("Unknown", true) ->
                    WineSuggestionSource.WEB_SEARCH
                else -> WineSuggestionSource.GEMMA
            }
        }

    private fun JSONObject.provinceValue(): String =
        optString("province").trim().takeIf(String::isNotBlank)
            // Read the pre-rename key so existing History entries are preserved.
            ?: optString("region").trim().takeIf(String::isNotBlank)
            ?: "Unknown"

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "uncork_history"
        const val KEY_ENTRIES = "entries"
        const val MAX_HISTORY_ENTRIES = 500
        const val LEGACY_REQUEST_LABEL = "Previous wine suggestion"
    }
}
