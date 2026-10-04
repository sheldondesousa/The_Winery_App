package com.sheldondesousa.uncork.data.knowledge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A place in a grape's geography tree, such as France, Bordeaux or Saint-Émilion, with the places beneath it. */
data class ProductionPlace(val name: String, val children: List<ProductionPlace>) {
    /** This place and everything beneath it, each with how deep it sits (the country is 0). */
    fun flattened(depth: Int = 0): List<Pair<ProductionPlace, Int>> =
        listOf(this to depth) + children.flatMap { it.flattened(depth + 1) }
}

/** One production step as written for one place: its text and how common the practice is. */
data class ProductionFact(val value: String, val status: String)

/** The facts recorded for one grape at one place. Only the steps that place actually defines are in [facts]. */
data class ProductionRecord(
    val id: String,
    val place: String,
    val facts: Map<String, ProductionFact>,
    val sources: List<String>,
    val confidence: Double?,
)

/** A grape and where it is grown, with the facts recorded for it. A grape with no [records] has no facts yet. */
data class ProductionGrape(val name: String, val root: ProductionPlace, val records: List<ProductionRecord>) {
    val hasFacts: Boolean get() = records.isNotEmpty()
}

/** A production step as shown for a place. [from] is the place the text comes from; [inherited] is true when it is not the place itself. */
data class ResolvedFact(val key: String, val value: String, val status: String, val from: String, val inherited: Boolean)

data class ResolvedProduction(val facts: List<ResolvedFact>, val sources: List<String>, val confidence: Double?)

/**
 * Wine production facts by country, then grape, then place. A place records only what differs from the place above it;
 * anything it does not define is taken from the nearest place above that does, and marked as inherited. A place with
 * nothing recorded anywhere above it has nothing to show, so nothing is made up.
 */
class WineProduction(val country: String, val grapes: List<ProductionGrape>) {

    fun grape(name: String): ProductionGrape? = grapes.firstOrNull { it.name == name }

    /** The places from the country down to [place] in [grape]'s tree, or null if [place] is not in it. */
    fun pathTo(grape: ProductionGrape, place: String): List<String>? {
        fun walk(node: ProductionPlace, trail: List<String>): List<String>? {
            val here = trail + node.name
            if (node.name == place) return here
            return node.children.firstNotNullOfOrNull { walk(it, here) }
        }
        return walk(grape.root, emptyList())
    }

    /** The production steps for [grape] at [place]: the closest place's wording for each step, most-specific first overrides. */
    fun resolve(grape: ProductionGrape, place: String): ResolvedProduction {
        val path = pathTo(grape, place) ?: return ResolvedProduction(emptyList(), emptyList(), null)
        val chain = path.mapNotNull { name -> grape.records.firstOrNull { it.place == name } }
        val merged = linkedMapOf<String, ResolvedFact>()
        chain.forEach { record ->
            record.facts.forEach { (key, fact) ->
                merged[key] = ResolvedFact(key, fact.value, fact.status, record.place, inherited = record.place != place)
            }
        }
        val ordered = merged.values.sortedBy { stepOrder(it.key) }
        return ResolvedProduction(
            facts = ordered,
            sources = chain.flatMap { it.sources }.distinct(),
            confidence = chain.lastOrNull { it.confidence != null }?.confidence,
        )
    }

    companion object {
        private const val ASSET = "knowledge/french_wine_production.json"

        // What the grape is and how its wine tastes come first, then the steps in the order a wine is made.
        private val STEP_ORDER = listOf(
            "grape_role", "style", "harvest", "sorting", "destemming_crushing", "pressing", "maceration", "clarification",
            "fermentation", "malolactic", "lees", "maturation", "blending", "filtration", "stabilisation", "bottling",
        )

        private fun stepOrder(key: String): Int = STEP_ORDER.indexOf(key).let { if (it < 0) STEP_ORDER.size else it }

        /** The heading shown for a production step. */
        fun stepLabel(key: String): String = when (key) {
            "grape_role" -> "Role of the grape"
            "style" -> "Style"
            "destemming_crushing" -> "Destemming and crushing"
            "malolactic" -> "Malolactic fermentation"
            "lees" -> "Lees"
            else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }

        /** How common a practice is, in words. */
        fun statusLabel(status: String): String = when (status) {
            "COMMON" -> "Common"
            "VARIABLE" -> "Varies by producer"
            "REQUIRED" -> "Required"
            "PERMITTED" -> "Permitted"
            "PROHIBITED" -> "Prohibited"
            "TRADITIONAL" -> "Traditional"
            "PRODUCER_SPECIFIC" -> "Producer specific"
            "REGIONAL_CHARACTERISTIC" -> "Regional characteristic"
            else -> status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        }

        fun fromJson(text: String): WineProduction {
            val json = JSONObject(text)
            return WineProduction(json.getString("country"), json.getJSONArray("grapes").objects().map { g ->
                ProductionGrape(
                    name = g.getString("name"),
                    root = place(g.getJSONObject("places")),
                    records = g.getJSONArray("records").objects().map { record(it) },
                )
            })
        }

        private fun place(json: JSONObject): ProductionPlace =
            ProductionPlace(json.getString("name"), json.getJSONArray("children").objects().map { place(it) })

        private fun record(json: JSONObject): ProductionRecord {
            val facts = json.getJSONObject("facts")
            return ProductionRecord(
                id = json.getString("id"),
                place = json.getString("place"),
                facts = facts.keys().asSequence().associateWith { key ->
                    facts.getJSONObject(key).let { ProductionFact(it.getString("value"), it.getString("status")) }
                },
                sources = json.getJSONArray("sources").let { a -> (0 until a.length()).map { a.getString(it) } },
                confidence = if (json.isNull("confidence")) null else json.getDouble("confidence"),
            )
        }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

        fun load(context: Context): WineProduction =
            context.assets.open(ASSET).bufferedReader().use { fromJson(it.readText()) }
    }
}

/** Loads the production facts once, off the main thread, the first time they are needed. */
class WineProductionProvider(private val context: Context) {
    private val mutex = Mutex()
    @Volatile private var loaded: WineProduction? = null

    suspend fun get(): WineProduction = loaded ?: mutex.withLock {
        loaded ?: withContext(Dispatchers.IO) { WineProduction.load(context) }.also { loaded = it }
    }
}
