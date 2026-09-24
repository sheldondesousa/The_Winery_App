package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.data.reviews.WineSelectionCriteria
import org.junit.Assert.assertEquals
import org.junit.Test

class WinePreferencesTest {
    @Test
    fun resolvedFieldsMapDirectlyOntoSelectionCriteria() {
        val preferences = WinePreferences(
            type = "red",
            country = "Italy",
            province = "Piedmont",
            body = "Full",
            tannin = "High",
            acidity = "Medium",
        )

        assertEquals(
            WineSelectionCriteria(
                wineType = "red",
                country = "Italy",
                province = "Piedmont",
                variety = null,
                body = "Full",
                tannin = "High",
                acidity = "Medium",
            ),
            preferences.toSelectionCriteria(),
        )
    }

    @Test
    fun unresolvedFieldsMapToNullRatherThanTheLiteralUnknownString() {
        val criteria = WinePreferences().toSelectionCriteria()

        assertEquals(WineSelectionCriteria(), criteria)
        assertEquals(false, criteria.hasAnyValue)
    }

    @Test
    fun aSinglePreferenceIsEnoughToMakeCriteriaNonEmpty() {
        val criteria = WinePreferences(country = "France").toSelectionCriteria()

        assertEquals("France", criteria.country)
        assertEquals(true, criteria.hasAnyValue)
    }
}
