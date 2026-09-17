package com.sheldondesousa.uncork.data.profile

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VarietyRegionProfileSeedAssetTest {
    @Test
    fun packagedAssetParsesEveryProfile() {
        val listType = Types.newParameterizedType(
            List::class.java,
            SeedVarietyRegionProfile::class.java,
        )
        val profiles = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
            .adapter<List<SeedVarietyRegionProfile>>(listType)
            .fromJson(File(SEED_ASSET_PATH).readText())

        assertEquals(4_119, profiles?.size)
        val nebbiolo = profiles?.single {
            it.country == "Italy" &&
                it.province == "Piedmont" &&
                it.variety == "Nebbiolo"
        }
        assertEquals("light to full", nebbiolo?.body)
        assertEquals("medium", nebbiolo?.tannin)
        assertEquals("medium to high", nebbiolo?.acidity)
        assertEquals(listOf("cherry", "spice", "licorice", "herbal"), nebbiolo?.flavorNotes)
        assertEquals("2026-09-15", nebbiolo?.generatedAt)
        assertNull(nebbiolo?.generatedBy)
    }

    private companion object {
        const val SEED_ASSET_PATH = "src/main/assets/country_province_variety_profiles.json"
    }
}
