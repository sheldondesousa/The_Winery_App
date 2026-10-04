package com.sheldondesousa.uncork.data.profile

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VarietyRegionProfileDao {
    @Query("SELECT * FROM VarietyRegionProfile WHERE country = :country AND province = :province AND variety = :variety")
    suspend fun find(country: String, province: String, variety: String): VarietyRegionProfile?

    @Query(
        """
        SELECT * FROM VarietyRegionProfile
        WHERE source = 'web_search'
          AND cachedWineName IS NOT NULL
          AND (:country IS NULL OR country = :country COLLATE NOCASE)
          AND (:province IS NULL OR province = :province COLLATE NOCASE)
          AND (:variety IS NULL OR variety = :variety COLLATE NOCASE)
          AND (:body IS NULL OR body = :body COLLATE NOCASE)
          AND (:tannin IS NULL OR tannin = :tannin COLLATE NOCASE)
          AND (:acidity IS NULL OR acidity = :acidity COLLATE NOCASE)
        ORDER BY generatedAt DESC
        LIMIT 50
        """,
    )
    suspend fun findCachedMatches(
        country: String?,
        province: String?,
        variety: String?,
        body: String?,
        tannin: String?,
        acidity: String?,
    ): List<VarietyRegionProfile>

    /** Profiles for a grape (any of its spellings) in one country that have at least one of body, tannin or acidity. */
    @Query(
        """
        SELECT * FROM VarietyRegionProfile
        WHERE country = :country COLLATE NOCASE
          AND variety COLLATE NOCASE IN (:varieties)
          AND (body IS NOT NULL OR tannin IS NOT NULL OR acidity IS NOT NULL)
        ORDER BY province ASC
        LIMIT :limit
        """,
    )
    suspend fun findStyles(country: String, varieties: List<String>, limit: Int): List<VarietyRegionProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: VarietyRegionProfile)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<VarietyRegionProfile>)

    @Query("SELECT COUNT(*) FROM VarietyRegionProfile")
    suspend fun count(): Int
}
