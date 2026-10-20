package com.sheldondesousa.uncork.data.reviews

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BottleReviewsTest {
    @Test fun splitsTheMergedReviewsAndDropsTheLabels() {
        assertEquals(listOf("Plum.", "Cedar."), BottleReviews.split("Review 1: Plum. || Review 2:  Cedar."))
    }

    @Test fun oneReviewOrUnknownIsNotEnoughToSummarise() {
        assertEquals(1, BottleReviews.split("Review 1: Plum.").size)
        assertTrue(BottleReviews.split("Unknown").isEmpty())
    }

    @Test fun sixOrFewerReviewsAreAllSent() {
        val reviews = (1..6).map { "r$it" }
        assertEquals(reviews, BottleReviews.pick(reviews))
    }

    @Test fun moreThanSixSendsTwoFromEachThird() {
        val reviews = (1..12).map { "r$it" }
        val picked = BottleReviews.pick(reviews, Random(3))
        assertEquals(6, picked.size)
        assertEquals(2, picked.count { it in reviews.subList(0, 4) })
        assertEquals(2, picked.count { it in reviews.subList(4, 8) })
        assertEquals(2, picked.count { it in reviews.subList(8, 12) })
    }
}
