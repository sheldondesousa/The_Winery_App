package com.sheldondesousa.uncork.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextEchoTest {
    @Test fun aCopiedOrInventedBlockIsRemovedFromTheReply() {
        val reply = "Typically you'll find other estates.\n\n<more_context>\nChâteau Margaux: Bordeaux, France\n</more_context>\n\nI can tell you about Château Margaux."
        assertEquals("Typically you'll find other estates.\n\nI can tell you about Château Margaux.", ContextEcho.strip(reply))
    }

    @Test fun aBlockStillBeingWrittenIsHiddenWhileStreaming() {
        assertEquals("Here you go.", ContextEcho.strip("Here you go.<more_context>\nChâteau"))
        assertEquals("Here you go.", ContextEcho.strip("Here you go.<more_con"))
    }

    @Test fun ordinaryTextIsUntouched() {
        assertEquals("Merlot is soft & fruity.", ContextEcho.strip("Merlot is soft & fruity."))
    }
}
