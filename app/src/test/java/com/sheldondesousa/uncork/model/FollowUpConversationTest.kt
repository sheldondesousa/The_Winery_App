package com.sheldondesousa.uncork.model

import com.sheldondesousa.uncork.data.knowledge.GrapeProfileInternal
import com.sheldondesousa.uncork.data.knowledge.WineriesDirectory
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-turn check of the Kotlin half: scripted conversations where later turns lean on earlier ones ("it", "there",
 * "And in France?"). Each follow-up is scored on whether Kotlin worked out what it was about and put the right notes in
 * front of Gemma. Gemma's own reply is not part of this test.
 */
class FollowUpConversationTest {
    private val base = GrapeProfileInternal.fromJsonLines(File("src/main/assets/knowledge/grape_profile_internal.jsonl").readText())
    private val directory = WineriesDirectory.fromCsv(File("src/main/assets/knowledge/wineries_directory.csv").readText())

    private class Turn(
        val question: String,
        val contains: List<String> = emptyList(),
        val rewritten: String? = null,
        val unchanged: Boolean = false,
        /** Kotlin adds nothing here by design: the notes went in earlier and Gemma answers from the conversation. */
        val leftToHistory: Boolean = false,
    )

    private val conversations: List<List<Turn>> = listOf(
        listOf(Turn("Tell me about Merlot.", listOf("Merlot")), Turn("What about its tannins?", listOf("Merlot"))),
        listOf(Turn("Tell me about Chardonnay.", listOf("Chardonnay")), Turn("Where in Burgundy is it grown?", listOf("Chardonnay"))),
        listOf(Turn("Tell me about Merlot in France.", listOf("Merlot")), Turn("And Aglianico?", listOf("Grape_Profile_Kaggle_Extracted"))),
        listOf(Turn("Which wineries are in Chile?", listOf("Chile")), Turn("Name a winery from there.", listOf("Chile"), rewritten = "Name a winery from Chile.")),
        listOf(
            Turn("Which US wineries make Chardonnay?"),
            Turn("And in France?", rewritten = "Which French wineries make Chardonnay?"),
        ),
        listOf(Turn("Tell me about Malbec.", listOf("Malbec")), Turn("And Merlot?", listOf("Merlot"))),
        listOf(Turn("Tell me about Malbec in Argentina.", listOf("Malbec")), Turn("Tell me more.", leftToHistory = true)),
        listOf(Turn("Tell me about Merlot.", listOf("Merlot")), Turn("Which wineries are in Bordeaux?", unchanged = true)),
        listOf(Turn("What is Malbec like in Argentina?", listOf("Malbec")), Turn("Does it age well?", listOf("Malbec"))),
        listOf(Turn("Tell me about Pinot Noir.", listOf("Pinot Noir")), Turn("Is it light or full-bodied?", listOf("Pinot Noir"))),
    )

    @Test fun followUpsKeepTheirContext() = kotlinx.coroutines.runBlocking {
        val failures = mutableListOf<String>()
        var turns = 0
        var followUps = 0
        var leftToHistory = 0
        conversations.forEachIndexed { index, conversation ->
            val extras = WineAskExtras(
                base,
                loadWineries = { directory },
                loadKaggleExtracted = { _, country -> listOf(KaggleExtractedProfile("Full", "Medium", "Medium", listOf("cherry"), "Test province", country)) },
            ) { _, _ -> null }
            conversation.forEachIndexed { turn, step ->
                val result = extras.lookup(step.question)
                turns++
                if (turn == 0) return@forEachIndexed
                followUps++
                if (step.leftToHistory) {
                    leftToHistory++
                    followUps--
                    println("Left to Gemma's memory (Kotlin added nothing): \"${step.question}\" -> ${result.outcome}")
                    return@forEachIndexed
                }
                val text = result.context.orEmpty() + result.refusal.orEmpty()
                val problems = buildList {
                    step.contains.filterNot { text.contains(it) }.forEach { add("missing \"$it\"") }
                    if (step.rewritten != null && result.rewritten != step.rewritten) add("rewrite was \"${result.rewritten}\"")
                    if (step.unchanged && result.rewritten != null) add("was rewritten to \"${result.rewritten}\"")
                }
                if (problems.isNotEmpty()) failures += "conversation ${index + 1}, \"${step.question}\": ${problems.joinToString("; ")}"
            }
        }
        println("Follow-ups that kept their context: ${followUps - failures.size} of $followUps (plus $leftToHistory left to Gemma's memory)")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
