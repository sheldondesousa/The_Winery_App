package com.sheldondesousa.uncork.model

import org.junit.Assert.*
import org.junit.Test

class WinePreferencesToGuidedTest {
    @Test fun chatAnswersBecomeTheSameSelectionsAsTheFindForm() {
        val criteria = WinePreferences(
            type = "rose", country = "France", province = "Provence", body = "Light-Bodied",
            tannin = "Smooth", acidity = "Crisp", sweetness = "Bone-Dry", variety = "Syrah",
        ).toGuidedCriteria()!!
        assertEquals("Rosé", criteria.wineType)
        assertEquals("France", criteria.country)
        assertEquals("Provence", criteria.province)
        assertEquals("Light-Bodied", criteria.body)
        assertEquals("Smooth", criteria.tannin)
        assertEquals("Crisp", criteria.acidity)
        assertEquals("Bone-Dry", criteria.sweetness)
        assertEquals("Syrah / Shiraz", criteria.variety)
    }

    @Test fun sweetTypeBecomesASweetnessAndUnknownOrUnsupportedValuesAreDropped() {
        val criteria = WinePreferences(type = "sweet", country = "Italy", body = "Huge").toGuidedCriteria()!!
        assertEquals("", criteria.wineType)
        assertEquals("Sweet", criteria.sweetness)
        assertEquals("", criteria.body)
        assertEquals("Italy", criteria.country)
    }

    @Test fun nothingResolvedMeansNoHandoff() {
        assertNull(WinePreferences().toGuidedCriteria())
    }
}
