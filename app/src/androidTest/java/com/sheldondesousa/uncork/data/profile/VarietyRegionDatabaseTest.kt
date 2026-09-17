package com.sheldondesousa.uncork.data.profile

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VarietyRegionDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: VarietyRegionDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            VarietyRegionDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun seedLoadsPackagedProfilesOrSafelySkipsWhenAssetIsAbsent() = runBlocking {
        val seedAssetIsPackaged = context.assets.list("")
            ?.contains("country_province_variety_profiles.json") == true

        seedFromAssetsIfEmpty(context, database)

        val dao = database.varietyRegionProfileDao()
        if (!seedAssetIsPackaged) {
            assertEquals(0, dao.count())
            return@runBlocking
        }

        assertEquals(4_119, dao.count())

        val nebbiolo = dao.find("Italy", "Piedmont", "Nebbiolo")
        assertNotNull(nebbiolo)
        assertEquals("Italy", nebbiolo?.country)
        assertEquals("light to full", nebbiolo?.body)
        assertEquals("medium", nebbiolo?.tannin)
        assertEquals("medium to high", nebbiolo?.acidity)
        assertEquals(
            listOf("cherry", "spice", "licorice", "herbal"),
            nebbiolo?.flavorNotes,
        )
        assertEquals("Unknown", nebbiolo?.generatedBy)
        assertEquals("2026-09-15", nebbiolo?.generatedAt)

        seedFromAssetsIfEmpty(context, database)
        assertEquals(4_119, dao.count())
    }

    @Test
    fun countryIsPartOfTheCompositeKeyAndLookupOrder() = runBlocking {
        val dao = database.varietyRegionProfileDao()
        val piedmontNebbiolo = VarietyRegionProfile(
            country = "Italy",
            province = "Piedmont",
            variety = "Nebbiolo",
            body = "full",
            tannin = "high",
            acidity = "high",
            flavorNotes = listOf("rose", "tar"),
            generatedBy = "test",
            generatedAt = "2026-09-15T00:00:00Z",
            source = "kaggle_derived",
        )
        dao.insertAll(
            listOf(
                piedmontNebbiolo,
                piedmontNebbiolo.copy(country = "United States"),
            ),
        )

        assertEquals(2, dao.count())
        assertEquals(piedmontNebbiolo, dao.find("Italy", "Piedmont", "Nebbiolo"))
        assertEquals(
            "United States",
            dao.find("United States", "Piedmont", "Nebbiolo")?.country,
        )
    }

    @Test
    fun webSearchProfileRoundTripsItsCachedWineOption() = runBlocking {
        val dao = database.varietyRegionProfileDao()
        val repository = VarietyRegionProfileRepository(dao)
        dao.insert(
            VarietyRegionProfile(
                country = "Italy",
                province = "Piedmont",
                variety = "Nebbiolo",
                body = "full",
                tannin = "high",
                acidity = "high",
                flavorNotes = listOf("rose", "tar"),
                generatedBy = "web-model",
                generatedAt = "2026-09-15T00:00:00Z",
                source = "web_search",
                cachedWineName = "Cached Barolo",
                cachedWinery = "Cached Winery",
                cachedSuggestedPairing = "Braised beef",
                webSummary = "Saved from a prior web search.",
            ),
        )

        val cached = repository.find("Italy", "Piedmont", "Nebbiolo")

        assertEquals("Cached Barolo", cached?.name)
        assertEquals("Cached Winery", cached?.winery)
        assertEquals("Saved from a prior web search.", cached?.webSummary)
    }
}
