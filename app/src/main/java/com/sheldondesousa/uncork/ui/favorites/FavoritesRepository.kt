package com.sheldondesousa.uncork.ui.favorites

import android.content.Context
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
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

    fun contains(wine: WineSuggestion): Boolean = load().any { it.favoriteKey == wine.favoriteKey }

    private val WineSuggestion.favoriteKey: String
        get() = listOf(winery, variety, region).joinToString("|") { it.trim().lowercase() }

    private fun WineSuggestion.toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("region", region)
        put("winery", winery)
        put("variety", variety)
        put("body", body)
        put("tannin", tannin)
        put("acidity", acidity)
        put("flavorNotes", flavorNotes)
        put("suggestedPairing", suggestedPairing)
        put("sourceRating", sourceRating)
        put("confidencePercent", confidencePercent ?: JSONObject.NULL)
        put("favoriteRating", favoriteRating ?: JSONObject.NULL)
    }

    private fun JSONObject.toSuggestion(): WineSuggestion = WineSuggestion(
        name = getString("name"),
        region = getString("region"),
        winery = optString("winery", getString("name")),
        variety = optString("variety", getString("name")),
        body = optString("body", "Unknown"),
        tannin = optString("tannin", "Unknown"),
        acidity = optString("acidity", "Unknown"),
        flavorNotes = optString("flavorNotes", "Unknown"),
        suggestedPairing = optString("suggestedPairing", "Unknown"),
        sourceRating = optString("sourceRating", "Unknown"),
        confidencePercent = optIntOrNull("confidencePercent"),
        favoriteRating = optIntOrNull("favoriteRating"),
        isFavorite = true,
    )

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    private companion object {
        const val PREFERENCES_NAME = "uncork_favorites"
        const val KEY_FAVORITES = "favorites"
    }
}
