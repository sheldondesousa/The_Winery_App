package com.sheldondesousa.uncork.ui.stageshow

import com.sheldondesousa.uncork.ui.components.displayStyleType
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestedPreferenceKeysTest {
    @Test
    fun cardTypesUseTheRequestedDisplayNames() {
        assertEquals("Rosé", "rose".displayStyleType())
        assertEquals("Dessert", "sweet".displayStyleType())
    }

    @Test
    fun onlyResolvedRequestKeysAppearOnGemmaDetails() {
        assertEquals(
            setOf("type", "country", "body", "flavor"),
            requestedPreferenceKeys(
                """{"type":"Red","country":"France","province":"Unknown","body":"Full-Bodied","flavor":"berry","occasion":"Unknown"}""",
            ),
        )
    }

    @Test
    fun educatorAnswerSplitsIntoSixProfileSections() {
        val answer = """
            **Overview:** A beginner-friendly overview.
            **Taste:** Fresh and crisp.
            **Where it's grown:** New Zealand, especially Marlborough.
            **Production facts:** Often made in stainless steel.
            **Flavours:** Citrus, herbs, and passion fruit.
            **Best pairings:** Goat cheese, fish, and salad.
        """.trimIndent()

        assertEquals(
            listOf("Overview", "Taste", "Where it's grown", "Production facts", "Flavours"),
            wineEducationSections(answer).map { it.first },
        )
    }

    @Test
    fun partialEducatorAnswerCanRenderBeforeGenerationFinishes() {
        assertEquals(
            listOf("Overview" to "A fresh, light-bodied red"),
            wineEducationSections("**Overview:** A fresh, light-bodied red"),
        )
    }
}
