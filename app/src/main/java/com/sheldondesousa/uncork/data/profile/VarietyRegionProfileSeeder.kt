package com.sheldondesousa.uncork.data.profile

import android.content.Context
import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun seedFromAssetsIfEmpty(
    context: Context,
    db: VarietyRegionDatabase,
) = withContext(Dispatchers.IO) {
    val dao = db.varietyRegionProfileDao()
    if (dao.count() > 0) return@withContext

    val json = try {
        context.assets.open(SEED_ASSET_NAME).bufferedReader().use { it.readText() }
    } catch (error: FileNotFoundException) {
        Log.w(TAG, "$SEED_ASSET_NAME is not packaged; skipping profile database seeding.", error)
        return@withContext
    }
    val listType = Types.newParameterizedType(List::class.java, SeedVarietyRegionProfile::class.java)
    val seedProfiles = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter<List<SeedVarietyRegionProfile>>(listType)
        .fromJson(json)
        ?: error("$SEED_ASSET_NAME did not contain a JSON array.")
    val profiles = seedProfiles.map { seed ->
        VarietyRegionProfile(
            country = seed.country,
            province = seed.province,
            variety = seed.variety,
            body = seed.body,
            tannin = seed.tannin,
            acidity = seed.acidity,
            flavorNotes = seed.flavorNotes,
            generatedBy = seed.generatedBy ?: UNKNOWN_GENERATOR,
            generatedAt = seed.generatedAt,
            source = seed.source,
        )
    }

    dao.insertAll(profiles)
}

internal data class SeedVarietyRegionProfile(
    val country: String,
    val province: String,
    val variety: String,
    val body: String?,
    val tannin: String?,
    val acidity: String?,
    @param:Json(name = "flavor_notes") val flavorNotes: List<String>,
    val source: String,
    @param:Json(name = "generated_at") val generatedAt: String,
    @param:Json(name = "generated_by") val generatedBy: String? = null,
)

private const val TAG = "ProfileDatabaseSeeder"
private const val SEED_ASSET_NAME = "country_province_variety_profiles.json"
private const val UNKNOWN_GENERATOR = "Unknown"
