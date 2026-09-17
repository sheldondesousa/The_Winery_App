package com.sheldondesousa.uncork.ui.favorites

import android.content.Context
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import org.json.JSONArray
import org.json.JSONObject

class FavoritesRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): List<WineSuggestion> = runCatching {
        val array = JSONArray(preferences.getString(KEY_FAVORITES, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toSuggestion()?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    fun add(suggestion: WineSuggestion): List<WineSuggestion> {
        val favorite = suggestion.copy(isFavorite = true)
        val updated = listOf(favorite) + load().filterNot { it.favoriteKey == favorite.favoriteKey }
        val array = JSONArray()
        updated.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_FAVORITES, array.toString()).apply()
        return updated
    }

    fun remove(suggestion: WineSuggestion): List<WineSuggestion> {
        val updated = load().filterNot { it.favoriteKey == suggestion.favoriteKey }
        val array = JSONArray()
        updated.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_FAVORITES, array.toString()).apply()
        return updated
    }

    fun contains(wine: WineSuggestion): Boolean = load().any { it.favoriteKey == wine.favoriteKey }

    private val WineSuggestion.favoriteKey: String
        get() = listOf(name, winery, country, province, variety)
            .joinToString("|") { it.trim().lowercase() }

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
    }

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
        isFavorite = true,
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
            // Read the pre-rename key so existing saved wines are preserved.
            ?: optString("region").trim().takeIf(String::isNotBlank)
            ?: "Unknown"

    private companion object {
        const val PREFERENCES_NAME = "uncork_favorites"
        const val KEY_FAVORITES = "favorites"
    }
}
