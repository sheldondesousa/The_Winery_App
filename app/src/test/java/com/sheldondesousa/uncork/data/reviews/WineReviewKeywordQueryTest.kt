package com.sheldondesousa.uncork.data.reviews

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WineReviewKeywordQueryTest {
    @Test
    fun buildsSafeFtsQueryFromUsefulUserTerms() {
        assertEquals(
            "\"mushroom\" AND \"risotto\"",
            WineReviewKeywordQuery.from("What wine would work with mushroom risotto?"),
        )
    }

    @Test
    fun preservesFullBodiedRedAsTheSearchCriteria() {
        assertEquals(
            "\"full\" AND \"bodied\" AND \"red\"",
            WineReviewKeywordQuery.from("I asked for a full-bodied red wine"),
        )
        assertEquals(
            "\"full\" OR \"bodied\" OR \"red\"",
            WineReviewKeywordQuery.from("I asked for a full-bodied red wine", matchAll = false),
        )
    }

    @Test
    fun scopesRoseToWineIdentityFieldsAndIgnoresClarificationFiller() {
        assertEquals(
            "{name variety} : \"rosé\"",
            WineReviewKeywordQuery.from("Rosé. No country or attribute preference"),
        )
    }

    @Test
    fun returnsNullWhenNoUsefulTermsRemain() {
        assertNull(WineReviewKeywordQuery.from("what wine would you have"))
    }
}
