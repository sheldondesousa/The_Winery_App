package com.sheldondesousa.uncork.ui.conversation

import org.junit.Assert.*
import org.junit.Test

class WineAskChatTest {
    @Test fun opensWithOneWelcomeNamingTheWineInBoldAndListingWhatTheSommelierCanHelpWith() {
        val messages = WineAskChat.initialMessages("Estate Syrah 2018")
        assertEquals(1, messages.size)
        val welcome = messages.single()
        assertEquals(MessageAuthor.Assistant, welcome.author)
        assertEquals(
            "Hi! I'm Uncork, your AI sommelier. I can help you with questions about **Estate Syrah 2018**.\n\n" +
                "%%I can help you with:%%\n" +
                "• Grape Varieties\n• Flavours and aromas\n• Wineries\n• Consumer reviews\n• Wine production",
            welcome.text,
        )
        assertTrue(welcome.quickReplies.isEmpty())
    }

    @Test fun markupCharactersInAWineNameCannotBreakTheFormatting() {
        val text = WineAskChat.welcome("Odd **Name## %%")
        assertTrue(text.contains("**Odd Name"))
        assertEquals(2, Regex("\\*\\*").findAll(text).count())
    }

    @Test fun fallsBackToThisWineWhenTheNameIsMissing() {
        assertTrue(WineAskChat.welcome("Unknown").contains("questions about this wine."))
        assertFalse(WineAskChat.welcome("").contains("**"))
    }

    @Test fun topicsDoNotPromiseFoodPairingsWhichAreOutOfScopeForNow() {
        assertTrue(WineAskChat.TOPICS.none { it.contains("pairing", ignoreCase = true) || it.contains("food", ignoreCase = true) })
    }
}
