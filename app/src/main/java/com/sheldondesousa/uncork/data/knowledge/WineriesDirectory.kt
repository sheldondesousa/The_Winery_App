package com.sheldondesousa.uncork.data.knowledge

import android.content.Context
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where a winery is, according to the Wineries_Directory: its country, the region within it and, for some wineries,
 * a sub-region inside that region (blank when the directory gives none).
 */
data class WineryLocation(val winery: String, val country: String, val region: String, val subRegion: String = "") {
    /** The most specific place first: "Sub-region, Region", or just the region when there is no sub-region. */
    val place: String get() = listOf(subRegion, region).filter { it.isNotBlank() }.joinToString(", ")
}

/** A country, region or winery group in the browsable directory, with how many wineries it holds. */
data class DirectoryCount(val name: String, val count: Int)

/**
 * Wineries_Directory: a reference list of wineries with their country and region (about 30,000). Used by the Ask
 * screen so Gemma can say where a winery is, and list wineries in a country or region when asked, only from what is
 * listed. A winery that is not listed says nothing either way: the list is not complete, so "not listed" never means
 * "not real".
 */
class WineriesDirectory(entries: List<WineryLocation>) {
    private class Keyed(val entry: WineryLocation, val name: String, val country: String, val region: String, val sub: String) {
        /** The region and the sub-region (when there is one): both are places a question can name. */
        val places: List<String> = listOf(region, sub).filter { it.isNotBlank() }.distinct()
    }

    // Each entry is normalised once; the lookups below all reuse these keys.
    private val keyed: List<Keyed> = entries.map {
        Keyed(it, GrapeProfileInternal.normalize(it.winery), GrapeProfileInternal.normalize(it.country), GrapeProfileInternal.normalize(it.region), GrapeProfileInternal.normalize(it.subRegion))
    }
    private val byName: Map<String, List<WineryLocation>> =
        keyed.groupBy({ it.name }, { it.entry })

    val size: Int = entries.size

    // A winery is found under its region and, when it has one, under its sub-region too.
    private val byPlace: Map<Pair<String, String>, List<WineryLocation>> =
        keyed.flatMap { k -> k.places.map { (k.country to it) to k.entry } }.groupBy({ it.first }, { it.second })
    private val byCountry: Map<String, List<WineryLocation>> =
        keyed.groupBy({ it.country }, { it.entry })
    private val regionNames: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        keyed.forEach { k -> k.places.forEach { getOrPut(it) { mutableSetOf() } += k.country } }
    }

    // "Château Ausone" is also found as "Ausone": the name without its leading Château/Domaine/Bodega-style word.
    // Cores that are everyday words, or are themselves a region name (Margaux), are left out so they never match a place.
    private val byCore: Map<String, List<WineryLocation>> = keyed.mapNotNull { k ->
        val words = k.name.split(' ')
        if (words.size < 2 || words.first() !in NAME_PREFIXES) return@mapNotNull null
        val core = words.drop(1).joinToString(" ")
        if (core.length < MIN_SINGLE_WORD || core in EVERYDAY_WORDS || core in regionNames) null else core to k.entry
    }.groupBy({ it.first }, { it.second })

    // Single words inside region names ("sonoma" in "Sonoma Valley"), for a place named more broadly than the directory labels it.
    private val regionWords: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        keyed.forEach { k ->
            k.places.flatMap { it.split(' ') }.filter { it.length >= MIN_SINGLE_WORD && it !in EVERYDAY_WORDS && it !in REGION_FILLER }
                .forEach { w -> getOrPut(w) { mutableSetOf() } += k.country }
        }
    }

    private val collator: Collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }

    /** Every country in the directory, alphabetised, with how many wineries each has. */
    fun countries(): List<DirectoryCount> =
        keyed.groupingBy { it.entry.country }.eachCount().map { DirectoryCount(it.key, it.value) }
            .sortedWith(compareBy(collator) { it.name })

    /** The regions (provinces) of a country, alphabetised, with how many wineries each has. */
    fun regions(country: String): List<DirectoryCount> =
        keyed.filter { it.entry.country == country }.groupingBy { it.entry.region }.eachCount()
            .map { DirectoryCount(it.key, it.value) }.sortedWith(compareBy(collator) { it.name })

    /** The sub-regions of a region, alphabetised, with how many wineries each has; empty when the region has none. */
    fun subRegions(country: String, region: String): List<DirectoryCount> =
        keyed.filter { it.entry.country == country && it.entry.region == region && it.entry.subRegion.isNotBlank() }
            .groupingBy { it.entry.subRegion }.eachCount().map { DirectoryCount(it.key, it.value) }
            .sortedWith(compareBy(collator) { it.name })

    /**
     * The winery names in one region of a country, alphabetised. With [subRegion] only that sub-region's wineries;
     * without it, only the wineries the directory places in the region itself (no sub-region), since the rest are
     * listed under their sub-region.
     */
    fun wineriesIn(country: String, region: String, subRegion: String? = null): List<String> =
        keyed.filter {
            it.entry.country == country && it.entry.region == region &&
                if (subRegion == null) it.entry.subRegion.isBlank() else it.entry.subRegion == subRegion
        }.map { it.entry.winery }.distinct().sortedWith(collator)

    /**
     * Locations for [winery]. When the wine's [country] is known, only entries in that country count; if the name is
     * listed only in other countries the result is empty, so a different winery with the same name is never offered.
     */
    fun find(winery: String, country: String? = null, limit: Int = MAX_LOCATIONS): List<WineryLocation> {
        val key = GrapeProfileInternal.normalize(winery)
        if (key.isBlank()) return emptyList()
        val matches = byName[key].orEmpty().ifEmpty { byCore[key].orEmpty() }
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
                if (phrase.length < MIN_SINGLE_WORD || phrase in REGION_SKIP) continue
                val countries = regionNames[phrase] ?: continue
                val country = if (hint != null && hint in countries) hint else countries.singleOrNull() ?: continue
                val sample = byPlace[country to phrase]?.firstOrNull() ?: continue
                return sample.country to (if (GrapeProfileInternal.normalize(sample.region) == phrase) sample.region else sample.subRegion)
            }
        }
        // No region carries this exact name ("Sonoma County"): use a word that several region names share ("Sonoma").
        for (token in tokens) {
            if (token.length < MIN_SINGLE_WORD || token in REGION_SKIP || token in REGION_FILLER) continue
            val countries = regionWords[token] ?: continue
            val country = if (hint != null && hint in countries) hint else countries.singleOrNull() ?: continue
            val original = keyed.firstOrNull { it.country == country && it.places.any { p -> " $p ".contains(" $token ") } }?.entry?.country ?: continue
            return original to token
        }
        return null
    }

    /** The directory's own region and sub-region names that a broad place word ("sonoma") stands for; empty when [region] is an exact region. */
    fun regionVariants(country: String, region: String): List<String> {
        val c = GrapeProfileInternal.normalize(country)
        val r = GrapeProfileInternal.normalize(region)
        if (r.isBlank() || byPlace.containsKey(c to r)) return emptyList()
        return poolIn(c, r).groupingBy { it.subRegion.ifBlank { it.region } }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Wineries in a country's region: the exact region, else every region whose name contains the place word. */
    private fun poolIn(country: String, region: String): List<WineryLocation> =
        byPlace[country to region] ?: keyed.filter { it.country == country && it.places.any { p -> " $p ".contains(" $region ") } }.map { it.entry }

    /**
     * A small, unranked sample of the wineries in a country, or in one of its regions. The directory has no ranking,
     * so the sample is spread by a fixed hash of the name rather than alphabetically. Never "the best" wineries.
     * Names in [exclude] are skipped, which is how "more" gets different wineries.
     */
    fun sampleIn(country: String, region: String? = null, limit: Int = 8, exclude: Set<String> = emptySet()): List<WineryLocation> {
        val c = GrapeProfileInternal.normalize(country)
        val pool = if (region.isNullOrBlank()) byCountry[c].orEmpty() else poolIn(c, GrapeProfileInternal.normalize(region))
        // The order is fixed, so skipping the names already [exclude]d (lower-case) gives the next page of the same list.
        return pool.sortedBy { GrapeProfileInternal.normalize(it.winery).hashCode().toLong() * 2654435761L % 2147483647L }
            .distinctBy { it.winery.lowercase() }.filter { it.winery.lowercase() !in exclude }.take(limit)
    }

    /**
     * A place written with its parents, most specific first: "Saint-Emilion, Bordeaux, France" for a sub-region,
     * "Bordeaux, France" for a region, "France" for a country. A name the directory does not know is kept as given.
     */
    fun placeLabel(country: String, place: String?): String {
        if (place.isNullOrBlank()) return country
        val p = GrapeProfileInternal.normalize(place)
        val hit = byPlace[GrapeProfileInternal.normalize(country) to p]?.firstOrNull()
            ?: return listOf(place, country).joinToString(", ")
        val parts = if (GrapeProfileInternal.normalize(hit.region) == p) listOf(hit.region) else listOf(hit.subRegion, hit.region)
        return (parts + hit.country).filter { it.isNotBlank() }.joinToString(", ")
    }

    fun countOf(country: String, region: String? = null): Int {
        val c = GrapeProfileInternal.normalize(country)
        return if (region.isNullOrBlank()) byCountry[c].orEmpty().size else poolIn(c, GrapeProfileInternal.normalize(region)).size
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
        // Words a winery name starts with that are not part of what people call it.
        private val NAME_PREFIXES = setOf("chateau", "domaine", "bodega", "bodegas", "tenuta", "cantina", "castello", "weingut", "clos", "quinta")
        // Place-name filler that never identifies a region on its own.
        private val REGION_FILLER = setOf("county", "district", "region", "province", "appellation", "coast", "states", "united")
        // Words skipped when matching a place, so a grape or "winery" is never read as one. Bordeaux and Burgundy are
        // real places, so they are not skipped here (they still are as winery names).
        private val REGION_SKIP: Set<String> by lazy { EVERYDAY_WORDS - setOf("bordeaux", "burgundy") }
        private const val ASSET = "knowledge/wineries_directory.csv"

        /**
         * Reads the directory by its header names, so the current file
         * (country, region, sub_region, winery) and the older three-column file (winery, country, region) both work. Rows with no winery (a country listed without any) are skipped.
         */
        fun fromCsv(text: String): WineriesDirectory {
            val lines = text.removePrefix("\uFEFF").lineSequence().iterator()
            if (!lines.hasNext()) return WineriesDirectory(emptyList())
            val header = parseCsvLine(lines.next()).map { it.trim().lowercase() }
            val wineryAt = header.indexOf("winery").takeIf { it >= 0 } ?: 0
            val countryAt = header.indexOf("country").takeIf { it >= 0 } ?: 1
            val regionAt = header.indexOf("region").takeIf { it >= 0 } ?: 2
            val subRegionAt = header.indexOf("sub_region")
            val entries = lines.asSequence().filter { it.isNotBlank() }.mapNotNull { line ->
                val cells = parseCsvLine(line)
                val winery = cells.getOrNull(wineryAt).orEmpty()
                if (winery.isBlank()) null
                else WineryLocation(
                    winery, cells.getOrNull(countryAt).orEmpty(), cells.getOrNull(regionAt).orEmpty(),
                    if (subRegionAt >= 0) cells.getOrNull(subRegionAt).orEmpty() else "",
                )
            }.toList()
            return WineriesDirectory(entries)
        }

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
