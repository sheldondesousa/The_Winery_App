package com.sheldondesousa.uncork.data.knowledge

import android.content.Context
import com.sheldondesousa.uncork.data.reviews.GrapeVarieties
import org.json.JSONObject
import java.text.Normalizer

/** Curated notes for one grape, written for beginners. Reliable for the grape in general, not for any single bottle. */
data class InternalGrapeProfile(
    val grape: String,
    val alsoKnownAs: String,
    val colour: String,
    val body: String,
    val acidity: String,
    val tannin: String,
    val sweetness: String,
    val alcohol: String,
    val aromas: String,
    val flavours: String,
    val foodPairings: String,
    val ageing: String,
    val origin: String,
    val keyRegions: String,
    val summary: String,
)

/**
 * What the knowledge bank found for a wine's variety. [grapes] holds one entry for a single grape and
 * one per component for a blend. [blendNote] explains how they were found when the variety is a blend
 * or a style label, so Gemma never presents a blend's grapes as certain when they are only typical.
 */
data class GrapeLookup(val grapes: List<InternalGrapeProfile>, val blendNote: String? = null) {
    companion object {
        val NONE = GrapeLookup(emptyList())
    }
}

class GrapeProfileInternal(entries: List<InternalGrapeProfile>) {
    private val index: Map<String, InternalGrapeProfile> = buildMap {
        entries.forEach { entry ->
            keysOf(entry).forEach { putIfAbsent(it, entry) }
        }
    }

    val size: Int = entries.size

    fun find(variety: String): GrapeLookup {
        val label = variety.trim()
        if (label.isBlank() || label.equals("Unknown", ignoreCase = true)) return GrapeLookup.NONE
        singleGrape(label)?.let { return GrapeLookup(listOf(it)) }

        STYLE_BLENDS[normalize(label)]?.let { (components, note) ->
            return GrapeLookup(components.mapNotNull(::singleGrape).distinct(), note)
        }

        val parts = label.split('-', ',', '/', '&').flatMap { it.split(" and ") }
            .map { it.replace(Regex("(?i)\\bblend\\b"), "").trim() }
            .filter { it.isNotBlank() }
        if (parts.size > 1 || label.contains("blend", ignoreCase = true)) {
            val grapes = parts.mapNotNull(::singleGrape).distinct()
            val note = if (parts.size > 1) {
                "\"$label\" is a blend. Notes are given for each grape named in the label; the exact proportions are not known."
            } else {
                "\"$label\" is a blend and its grapes are not specified."
            }
            return GrapeLookup(grapes, note)
        }
        return GrapeLookup.NONE
    }

    /**
     * Grapes named in free text (a user's question), longest name first, each grape once. Aliases count, so
     * "shiraz" finds the Syrah entry. Words that are not a grape name are ignored.
     */
    fun findMentioned(text: String): List<InternalGrapeProfile> {
        val tokens = normalize(text).split(' ').filter { it.isNotBlank() }
        val used = BooleanArray(tokens.size)
        val found = linkedMapOf<String, InternalGrapeProfile>()
        for (length in MAX_NAME_WORDS downTo 1) {
            for (start in 0..tokens.size - length) {
                if ((start until start + length).any { used[it] }) continue
                val phrase = tokens.subList(start, start + length).joinToString(" ")
                val grape = index[phrase] ?: singleGrape(phrase) ?: continue
                (start until start + length).forEach { used[it] = true }
                found.putIfAbsent(grape.grape, grape)
            }
        }
        return found.values.toList()
    }

    private fun singleGrape(name: String): InternalGrapeProfile? {
        index[normalize(name)]?.let { return it }
        // The database spells one grape many ways (Syrah/Shiraz, Garnacha/Grenache…); the Find variety
        // list already merged those, so try every name in the merged group.
        val group = GrapeVarieties.all.firstOrNull { grape ->
            grape.databaseNames.any { it.equals(name, ignoreCase = true) } || grape.name.equals(name, ignoreCase = true)
        } ?: return null
        val candidates = group.databaseNames + group.name.split(" / ")
        return candidates.firstNotNullOfOrNull { index[normalize(it)] }
    }

    companion object {
        private const val MAX_NAME_WORDS = 3
        private const val ASSET = "knowledge/grape_profile_internal.jsonl"

        fun load(context: Context): GrapeProfileInternal =
            context.assets.open(ASSET).bufferedReader().use { fromJsonLines(it.readText()) }

        fun fromJsonLines(text: String): GrapeProfileInternal = GrapeProfileInternal(
            text.lineSequence().filter { it.isNotBlank() }.map { line ->
                val json = JSONObject(line)
                fun str(key: String) = json.optString(key).trim()
                InternalGrapeProfile(
                    grape = str("grape"),
                    alsoKnownAs = str("also_known_as"),
                    colour = str("colour"),
                    body = str("body"),
                    acidity = str("acidity"),
                    tannin = str("tannin"),
                    sweetness = str("sweetness"),
                    alcohol = str("alcohol_abv"),
                    aromas = str("aromas"),
                    flavours = str("flavours"),
                    foodPairings = str("food_pairings"),
                    ageing = str("ageing_potential"),
                    origin = listOf(str("origin_region"), str("origin_country")).filter { it.isNotBlank() }.joinToString(", "),
                    keyRegions = str("key_regions"),
                    summary = str("summary"),
                )
            }.toList(),
        )

        // Typical grapes behind common style labels. These are style definitions, not facts about a bottle.
        private val STYLE_BLENDS: Map<String, Pair<List<String>, String>> = mapOf(
            "bordeaux style red blend" to bordeauxRed("Bordeaux-style red"),
            "meritage" to bordeauxRed("Meritage"),
            "cabernet blend" to bordeauxRed("Cabernet blend"),
            "bordeaux style white blend" to (listOf("Sauvignon Blanc", "Sémillon") to
                "A Bordeaux-style white is typically Sauvignon Blanc and Sémillon; the exact grapes in this bottle are not known."),
            "rhone style red blend" to gsm("Rhône-style red"),
            "g s m" to gsm("GSM"),
            "rhone style white blend" to (listOf("Viognier", "Marsanne", "Roussanne") to
                "A Rhône-style white typically uses Viognier, Marsanne and Roussanne; the exact grapes in this bottle are not known."),
            "champagne blend" to (listOf("Chardonnay", "Pinot Noir", "Pinot Meunier") to
                "A Champagne-style blend typically uses Chardonnay, Pinot Noir and Pinot Meunier; the exact grapes in this bottle are not known."),
        )

        private fun bordeauxRed(label: String) = listOf("Cabernet Sauvignon", "Merlot", "Cabernet Franc") to
            "$label wines are typically Cabernet Sauvignon, Merlot and Cabernet Franc; the exact grapes in this bottle are not known."

        private fun gsm(label: String) = listOf("Grenache", "Syrah", "Mourvèdre") to
            "$label wines are typically Grenache, Syrah and Mourvèdre; the exact grapes in this bottle are not known."

        internal fun normalize(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        private fun keysOf(entry: InternalGrapeProfile): List<String> {
            val names = entry.grape.split("/") +
                entry.alsoKnownAs.split(";", ",").map { it.replace(Regex("\\(.*?\\)"), "") }
            return names.map { normalize(it) }.filter { it.isNotBlank() }
        }
    }
}
