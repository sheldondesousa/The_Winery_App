package com.sheldondesousa.uncork.data.knowledge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where a winery is, according to the Wineries_Directory: its country and the region within it. */
data class WineryLocation(val winery: String, val country: String, val region: String)

/**
 * Wineries_Directory: a reference list of wineries with their country and region (about 30,000). Used by the Ask
 * screen so Gemma can say where a winery is, and list wineries in a country or region when asked, only from what is
 * listed. A winery that is not listed says nothing either way: the list is not complete, so "not listed" never means
 * "not real".
 */
class WineriesDirectory(entries: List<WineryLocation>) {
    private val byName: Map<String, List<WineryLocation>> =
        entries.groupBy { GrapeProfileInternal.normalize(it.winery) }

    val size: Int = entries.size

    private val byPlace: Map<Pair<String, String>, List<WineryLocation>> =
        entries.groupBy { GrapeProfileInternal.normalize(it.country) to GrapeProfileInternal.normalize(it.region) }
    private val byCountry: Map<String, List<WineryLocation>> =
        entries.groupBy { GrapeProfileInternal.normalize(it.country) }
    private val regionNames: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        entries.forEach { e ->
            val region = GrapeProfileInternal.normalize(e.region)
            if (region.isNotBlank()) getOrPut(region) { mutableSetOf() } += GrapeProfileInternal.normalize(e.country)
        }
    }

    /**
     * Locations for [winery]. When the wine's [country] is known, only entries in that country count; if the name is
     * listed only in other countries the result is empty, so a different winery with the same name is never offered.
     */
    fun find(winery: String, country: String? = null, limit: Int = MAX_LOCATIONS): List<WineryLocation> {
        val key = GrapeProfileInternal.normalize(winery)
        if (key.isBlank()) return emptyList()
        val matches = byName[key].orEmpty()
        val wanted = country?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
            ?.let { GrapeProfileInternal.normalize(it) }
        val filtered = if (wanted == null) matches else matches.filter { GrapeProfileInternal.normalize(it.country) == wanted }
        return filtered.take(limit)
    }

    /**
     * Wineries from the directory that are named in a question. Longest name first. A one-word winery name must be
     * at least six letters and not an everyday wine word, so "wine" or "estate" never matches. When [country] is known
     * only wineries there count.
     */
    fun findMentioned(text: String, country: String? = null, limit: Int = 3): List<WineryLocation> {
        val tokens = GrapeProfileInternal.normalize(text).split(' ').filter { it.isNotBlank() }
        val used = BooleanArray(tokens.size)
        val found = linkedMapOf<String, WineryLocation>()
        for (length in MAX_NAME_WORDS downTo 1) {
            for (start in 0..tokens.size - length) {
                if ((start until start + length).any { used[it] }) continue
                val phrase = tokens.subList(start, start + length).joinToString(" ")
                if (length == 1 && (phrase.length < MIN_SINGLE_WORD || phrase in EVERYDAY_WORDS)) continue
                val hit = find(phrase, country, limit = 1).firstOrNull() ?: continue
                (start until start + length).forEach { used[it] = true }
                found.putIfAbsent(phrase, hit)
                if (found.size >= limit) return found.values.toList()
            }
        }
        return found.values.toList()
    }

    /** A region named in a question, with its country (the one in [countryHint] if the region name exists there). */
    fun regionMentioned(text: String, countryHint: String? = null): Pair<String, String>? {
        val tokens = GrapeProfileInternal.normalize(text).split(' ').filter { it.isNotBlank() }
        val hint = countryHint?.takeIf { it.isNotBlank() }?.let { GrapeProfileInternal.normalize(it) }
        for (length in MAX_REGION_WORDS downTo 1) {
            for (start in 0..tokens.size - length) {
                val phrase = tokens.subList(start, start + length).joinToString(" ")
                if (phrase.length < MIN_SINGLE_WORD || phrase in EVERYDAY_WORDS) continue
                val countries = regionNames[phrase] ?: continue
                val country = if (hint != null && hint in countries) hint else countries.singleOrNull() ?: continue
                val sample = byPlace[country to phrase]?.firstOrNull() ?: continue
                return sample.country to sample.region
            }
        }
        return null
    }

    /**
     * A small, unranked sample of the wineries in a country, or in one of its regions. The directory has no ranking,
     * so the sample is spread by a fixed hash of the name rather than alphabetically. Never "the best" wineries.
     */
    fun sampleIn(country: String, region: String? = null, limit: Int = 8): List<WineryLocation> {
        val c = GrapeProfileInternal.normalize(country)
        val pool = if (region.isNullOrBlank()) byCountry[c].orEmpty() else byPlace[c to GrapeProfileInternal.normalize(region)].orEmpty()
        return pool.sortedBy { GrapeProfileInternal.normalize(it.winery).hashCode().toLong() * 2654435761L % 2147483647L }.take(limit)
    }

    fun countOf(country: String, region: String? = null): Int {
        val c = GrapeProfileInternal.normalize(country)
        return if (region.isNullOrBlank()) byCountry[c].orEmpty().size else byPlace[c to GrapeProfileInternal.normalize(region)].orEmpty().size
    }

    companion object {
        const val MAX_LOCATIONS = 3
        private const val MAX_NAME_WORDS = 5
        private const val MAX_REGION_WORDS = 3
        private const val MIN_SINGLE_WORD = 6
        private val EVERYDAY_WORDS = setOf(
            "winery", "wineries", "vineyard", "vineyards", "estate", "estates", "cellars", "domaine", "chateau", "bodega",
            "bodegas", "tenuta", "cantina", "producer", "producers", "country", "region", "valley", "mountain", "wines",
            "cabernet", "chardonnay", "sauvignon", "merlot", "riesling", "burgundy", "bordeaux",
        )
        private const val ASSET = "knowledge/wineries_directory.csv"

        fun fromCsv(text: String): WineriesDirectory = WineriesDirectory(
            text.lineSequence().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
                val cells = parseCsvLine(line)
                if (cells.size < 3 || cells[0].isBlank()) null else WineryLocation(cells[0], cells[1], cells[2])
            }.toList(),
        )

        fun load(context: Context): WineriesDirectory =
            context.assets.open(ASSET).bufferedReader().use { fromCsv(it.readText()) }

        /** Minimal CSV: commas separate cells and double quotes may wrap a cell that contains a comma. */
        internal fun parseCsvLine(line: String): List<String> {
            val cells = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { cells += current.toString(); current.clear() }
                    else -> current.append(c)
                }
                i++
            }
            cells += current.toString()
            return cells
        }
    }
}

/** Loads the (largish) directory once, off the main thread, the first time it is needed. */
class WineriesDirectoryProvider(private val context: Context) {
    private val mutex = Mutex()
    @Volatile private var loaded: WineriesDirectory? = null

    suspend fun get(): WineriesDirectory = loaded ?: mutex.withLock {
        loaded ?: withContext(Dispatchers.IO) { WineriesDirectory.load(context) }.also { loaded = it }
    }
}
