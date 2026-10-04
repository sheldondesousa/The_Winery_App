package com.sheldondesousa.uncork.ui.directory

import com.sheldondesousa.uncork.data.knowledge.WineriesDirectory
import java.io.File
import java.text.Collator
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class WineryDirectoryBrowseTest {
    private val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }

    /** Alphabetical the way a person reads it: ignoring case and accents ("Añelo" sorts with "An", not after "Z"). */
    private fun assertAlphabetical(names: List<String>) {
        names.zipWithNext().forEach { (a, b) -> assertTrue("$a should come before $b", collator.compare(a, b) <= 0) }
    }

    private val directory = WineriesDirectory.fromCsv(File("src/main/assets/knowledge/wineries_directory.csv").readText())

    @Test fun countriesAreAlphabeticalWithTheirWineryCounts() {
        val countries = directory.countries()
        assertEquals(44, countries.size)
        val names = countries.map { it.name }
        assertAlphabetical(names)
        assertEquals("Argentina", names.first())
        assertTrue(countries.first { it.name == "France" }.count > 5_000)
        assertEquals(directory.size, countries.sumOf { it.count })
    }

    @Test fun regionsOfACountryAreAlphabeticalAndAddUpToTheCountry() {
        val regions = directory.regions("Argentina")
        val names = regions.map { it.name }
        assertTrue("Mendoza" in names)
        assertAlphabetical(names)
        assertEquals(directory.countries().first { it.name == "Argentina" }.count, regions.sumOf { it.count })
        assertTrue(directory.regions("Nowhere").isEmpty())
    }

    @Test fun wineriesOfARegionAreAlphabeticalAndAllBelongToIt() {
        val wineries = directory.wineriesIn("Argentina", "Mendoza")
        assertTrue(wineries.size >= 9)
        assertAlphabetical(wineries)
        assertEquals(wineries.distinct(), wineries)
        assertTrue(directory.wineriesIn("Argentina", "Not a region").isEmpty())
    }

    @Test fun headersKeepTheCountryAsTheTitleAndPutTheRestInASmallSubtext() {
        assertEquals(DirectoryHeader("Country", null), directoryHeader(null, null))
        assertEquals(DirectoryHeader("France", "Regions"), directoryHeader("France", null))
        assertEquals(DirectoryHeader("France", "Bordeaux > Wineries"), directoryHeader("France", "Bordeaux"))
    }
}
