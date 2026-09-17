package com.sheldondesousa.uncork.model

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sheldondesousa.uncork.data.reviews.WineReviewRepository
import com.sheldondesousa.uncork.ui.conversation.ChatMessage
import com.sheldondesousa.uncork.ui.conversation.ConversationResponder
import com.sheldondesousa.uncork.ui.conversation.MessageAuthor
import com.sheldondesousa.uncork.ui.conversation.WineSuggestion
import com.sheldondesousa.uncork.ui.conversation.WineSuggestionSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KaggleConversationResponderIntegrationTest {
    @Test
    fun threeGemmaOptionsCanLoadSimilarWinesFromTheBundledDatabase() = runBlocking {
        val gemmaOptions = listOf(
            WineSuggestion(
                name = "Barolo",
                country = "Italy",
                province = "Piedmont",
                variety = "Nebbiolo",
            ),
            WineSuggestion(
                name = "Brunello di Montalcino",
                country = "Italy",
                province = "Tuscany",
                variety = "Sangiovese",
            ),
            WineSuggestion(
                name = "Rioja Reserva",
                country = "Spain",
                province = "Rioja",
                variety = "Tempranillo",
            ),
        )
        val gemma = ConversationResponder {
            ChatMessage(
                id = 1L,
                author = MessageAuthor.Assistant,
                text = "Here are three structured red wines.",
                suggestions = gemmaOptions,
                stageOneOutput = true,
            )
        }
        val repository = WineReviewRepository(ApplicationProvider.getApplicationContext())
        repository.prepare()
        val responder = KaggleConversationResponder(gemma, repository)

        val initial = responder.replyTo("Recommend three structured red wines")
        val enthusiasts = responder.replyTo("Yes, please")

        assertEquals(3, initial.suggestions.size)
        assertTrue(initial.followUpText?.contains("wine enthusiasts") == true)
        assertEquals(3, enthusiasts.suggestions.size)
        assertTrue(enthusiasts.suggestions.all { it.source == WineSuggestionSource.KAGGLE })
        assertEquals(
            listOf(
                "Gaja 2007 Sori San Lorenzo Nebbiolo (Langhe)",
                "Gaja 2007 Sori Tildin Nebbiolo (Langhe)",
                "Mascarello Giuseppe e Figlio 2008 Ca d'Morissio Riserva  (Barolo)",
            ).map(::normalizeWineName),
            enthusiasts.suggestions.map { normalizeWineName(it.name) },
        )
        assertTrue(enthusiasts.suggestions.all {
            it.country == "Italy" && it.province == "Piedmont" && it.variety == "Nebbiolo"
        })
    }

    private fun normalizeWineName(value: String): String = value
        .replace("ì", "i")
        .replace("à", "a")
}
