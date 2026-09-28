package com.sheldondesousa.uncork.model

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import android.content.Context
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Requires the real downloaded model; run explicitly on a provisioned emulator. */
class GemmaLiveHandoverSmokeTest {
    @Test
    fun liveQuestionThenCanonicalHandback() = runBlocking {
        assumeTrue("Opt in with -e liveGemma true on a provisioned emulator",
            InstrumentationRegistry.getArguments().getString("liveGemma") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ModelFileManager(context)
        assertTrue("Provision the model before this explicit live eval", manager.isModelReady())
        val transcript = File(context.filesDir, "live-handover-smoke.txt")
        transcript.writeText("Live model handover smoke test\n")
        GemmaConversationResponder(context, manager.modelFile).use { responder ->
            suspend fun turn(input: String): String {
                transcript.appendText("USER: $input\n")
                val result = responder.replyTo(input).text
                transcript.appendText("ASSISTANT: $result\n\n")
                return result
            }
            assertEquals(ChatFlowText.Q1_TYPE, turn("find a wine"))
            val answer = turn("Does tannin cause headaches?")
            assertTrue("Expected model answer in addition to pending question", answer.length > ChatFlowText.Q1_TYPE.length)
            assertTrue("Current implementation automatically re-appends Kotlin question", answer.endsWith(ChatFlowText.Q1_TYPE))
        }
        GemmaConversationResponder(context, manager.modelFile).use { responder ->
            responder.replyTo("curious")
            transcript.appendText("NEW SESSION: Curious\n")
            val answer = responder.replyTo("Is Malbec always full-bodied?").text
            transcript.appendText("ASSISTANT: $answer\n")
            assertTrue(answer.isNotBlank())
            val handback = responder.replyTo("let's find a wine").text
            transcript.appendText("HANDBACK: $handback\n")
            assertTrue(handback.contains(ChatFlowText.Q1_TYPE))
            assertEquals(ChatFlowText.Q2_COUNTRY, responder.replyTo("red").text)
            transcript.appendText("POST-HANDBACK: red advances to country question\n")
        }
    }
}
