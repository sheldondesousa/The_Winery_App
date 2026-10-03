package com.sheldondesousa.uncork.ui.conversation

import org.junit.Assert.*
import org.junit.Test

class WineAskChatTest {
    @Test fun opensWithOneWelcomeNamingTheWineInBoldAndListingWhatTheSommelierCanHelpWith() {
        val messages = WineAskChat.initialMessages("Estate Syrah 2018")
        assertEquals(1, messages.size)
        val welcome = messages.single()
        assertEquals(MessageAuthor.Assistant, welcome.author)
        assertTrue(welcome.text.startsWith(
            "Hi there! I'm Uncork, your AI sommelier, here to help you with questions about **Estate Syrah 2018** or something from the wine world.",
        ))
        assertTrue(welcome.text.contains("%%I can help with:%%"))
        listOf("This wine", "Reviews", "Grape varieties", "Aromas and flavours").forEach {
            assertTrue("missing $it", welcome.text.contains("• $it"))
        }
        assertTrue(welcome.quickReplies.isEmpty())
    }

    @Test fun markupCharactersInAWineNameCannotBreakTheFormatting() {
        val text = WineAskChat.welcome("Odd **Name## %%")
        assertTrue(text.contains("**Odd Name"))
        assertEquals(2, Regex("\\*\\*").findAll(text).count())
    }

    @Test fun fallsBackToThisParticularWineWhenTheNameIsMissing() {
        assertTrue(WineAskChat.welcome("Unknown").contains("questions about this particular wine or something"))
        assertFalse(WineAskChat.welcome("").contains("**"))
    }

    @Test fun topicsDoNotPromiseFoodPairingsWhichAreOutOfScopeForNow() {
        assertTrue(WineAskChat.TOPICS.none { it.contains("pairing", ignoreCase = true) || it.contains("food", ignoreCase = true) })
    }
}
