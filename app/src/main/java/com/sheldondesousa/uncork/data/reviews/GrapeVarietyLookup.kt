package com.sheldondesousa.uncork.data.reviews

import java.text.Normalizer

/** Finds grapes from the Find variety list in free text, so a question about any grape can be recognised. */
object GrapeVarietyLookup {
    // Names that are also everyday words (a flavour, a place, an adjective); a question using them is not about the grape.
    private val EVERYDAY_WORDS = setOf("melon", "mission", "symphony", "diamond", "norton", "apple", "other", "rebo", "baga")
    private const val MAX_WORDS = 3

    private val byName: Map<String, GrapeVariety> by lazy {
        buildMap {
            GrapeVarieties.all.forEach { grape ->
                (grape.name.split(" / ") + grape.databaseNames).forEach { name ->
                    val key = normalize(name)
                    if (key.isNotBlank() && key !in EVERYDAY_WORDS) putIfAbsent(key, grape)
                }
            }
        }
    }

    /** Grapes named in [text], longest name first, each grape once. */
    fun findMentioned(text: String): List<GrapeVariety> {
        val tokens = normalize(text).split(' ').filter { it.isNotBlank() }
        val used = BooleanArray(tokens.size)
        val found = linkedMapOf<String, GrapeVariety>()
        for (length in MAX_WORDS downTo 1) {
            for (start in 0..tokens.size - length) {
                if ((start until start + length).any { used[it] }) continue
                val grape = byName[tokens.subList(start, start + length).joinToString(" ")] ?: continue
                (start until start + length).forEach { used[it] = true }
                found.putIfAbsent(grape.name, grape)
            }
        }
        return found.values.toList()
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
