package com.sheldondesousa.uncork.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteJsonObjectsTest {
    @Test
    fun publishesFirstObjectBeforeArrayAndLaterObjectsFinish() {
        val stream = CompleteJsonObjects()
        assertTrue(stream.append("[WINE_CARDS][{\"name\":\"First").isEmpty())
        assertEquals(listOf("{\"name\":\"First\"}"), stream.append("\"},{\"name\":\"Sec"))
        assertEquals(listOf("{\"name\":\"Second\"}"), stream.append("ond\"}][/WINE_CARDS]"))
        assertTrue(stream.append("").isEmpty())
    }

    @Test
    fun handlesQuotesBracesAndEscapesAcrossEveryPossibleChunkBoundary() {
        val first = """{"name":"A \"quoted\" wine","summary":"{fruit} and \\ spice","flavor_notes":["berry"],"extra":{"a":1}}"""
        val second = """{"name":"Second"}"""
        val input = "[WINE_CARDS][$first,$second][/WINE_CARDS]"
        for (split in 0..input.length) {
            val stream = CompleteJsonObjects()
            assertEquals(listOf(first, second), stream.append(input.take(split)) + stream.append(input.drop(split)))
        }
        val stream = CompleteJsonObjects()
        assertEquals(listOf(first, second), input.flatMap { stream.append(it.toString()) })
    }

    @Test
    fun truncatedLastCardNeverBecomesClickable() {
        val stream = CompleteJsonObjects()
        assertEquals(listOf("{\"name\":\"Ready\"}"), stream.append("[{\"name\":\"Ready\"},{\"name\":\"Unfinished"))
        assertTrue(stream.append("").isEmpty())
    }

    @Test
    fun ignoresAnUnmatchedPreambleObjectBeforeTheCardMarker() {
        val stream = CompleteJsonObjects()
        assertTrue(stream.append("Unwanted preamble {\"unfinished\":true ").isEmpty())
        assertEquals(
            listOf("{\"name\":\"Recovered\"}"),
            stream.append("[WINE_CARDS][{\"name\":\"Recovered\"}][/WINE_CARDS]"),
        )
        assertEquals("marker", stream.startMode)
        assertEquals(0, stream.unfinishedObjectDepth)
    }

    @Test
    fun supportsAMarkerlessRawArrayAcrossChunkBoundaries() {
        val input = "Some prose [  {\"name\":\"First\"},{\"name\":\"Second\"}]"
        for (split in 0..input.length) {
            val stream = CompleteJsonObjects()
            assertEquals(
                listOf("{\"name\":\"First\"}", "{\"name\":\"Second\"}"),
                stream.append(input.take(split)) + stream.append(input.drop(split)),
            )
            assertEquals("raw_array", stream.startMode)
        }
    }
}
