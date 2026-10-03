package com.sheldondesousa.uncork.data.reviews

import kotlin.random.Random

/** One sampled critic review: where it came from, its score and a short excerpt. */
data class SampledReview(val province: String, val points: Int?, val excerpt: String)

/** One third of the score range (top, middle or bottom) with the reviews drawn from it. */
data class ReviewBandDigest(
    val name: String,
    val minPoints: Int?,
    val maxPoints: Int?,
    val poolSize: Int,
    val reviews: List<SampledReview>,
)

/**
 * An even-handed sample of the critic reviews of one grape from one country: the same number drawn from the
 * highest-scored, middle and lowest-scored thirds, so Gemma sees praise, average and critical opinions and
 * not just the top-scoring reviews. Describes the grape in that country, never one particular bottle.
 */
data class VarietyCountryDigest(
    val variety: String,
    val country: String,
    val totalReviews: Int,
    val averagePoints: Double?,
    val bands: List<ReviewBandDigest>,
) {
    val isEmpty: Boolean get() = bands.all { it.reviews.isEmpty() }
}

/** A candidate review from the pool, before its text is loaded. */
data class PoolReview(val id: Long, val points: Int?, val province: String)

internal object ReviewExcerpts {
    const val MAX_CHARS = 200

    /** First review only, cut at a word boundary so Gemma is not handed half a word. */
    fun shorten(reviewSummary: String, maxChars: Int = MAX_CHARS): String {
        val first = reviewSummary.substringBefore("||")
            .replace(Regex("^\\s*Review\\s*\\d+\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ").trim()
        if (first.length <= maxChars) return first
        val cut = first.take(maxChars)
        return cut.substringBeforeLast(' ', cut).trimEnd(',', ';', ':', '-') + "…"
    }
}

/** Picks the same number of reviews from each third of the score range. Pure, so it is easy to test. */
internal object ReviewSampler {
    const val PER_BAND = 12
    private val NAMES = listOf("highest-scored third", "middle third", "lowest-scored third")

    class Band(val name: String, val pool: List<PoolReview>, val picked: List<PoolReview>)

    /**
     * Ranks the pool by score (ties broken by a seeded hash, not by name or id), splits it into thirds by
     * rank, and draws up to [perBand] reviews at random from each third, using [seed] so the same wine gets
     * the same sample every time. Reviews without a score are ignored.
     */
    fun sample(pool: List<PoolReview>, seed: Long, perBand: Int = PER_BAND): List<Band> {
        val scored = pool.filter { it.points != null }
            .sortedWith(compareByDescending<PoolReview> { it.points }.thenBy { tieBreak(it.id, seed) })
        if (scored.isEmpty()) return emptyList()
        val thirds = if (scored.size < 3) listOf(scored) else {
            val a = scored.size / 3
            val b = scored.size * 2 / 3
            listOf(scored.subList(0, a), scored.subList(a, b), scored.subList(b, scored.size))
        }
        return thirds.mapIndexed { index, band ->
            val random = Random(seed + index * 7919L)
            Band(NAMES[index], band, band.shuffled(random).take(perBand).sortedByDescending { it.points })
        }
    }

    fun tieBreak(id: Long, seed: Long): Long = (id * GuidedReviewQuery.hashMultiplier(seed)) % 2147483647L
}
