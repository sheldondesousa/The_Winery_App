package com.sheldondesousa.uncork.ui.guided

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sheldondesousa.uncork.data.reviews.GuidedReviewQuery
import com.sheldondesousa.uncork.model.GuidedGemmaResponse
import com.sheldondesousa.uncork.ui.stageshow.toStageWine
import com.sheldondesousa.uncork.ui.stageshow.toWineSuggestion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuidedSelectionIntegrationTest {
    private val criteria = GuidedCriteria("France", "Bordeaux", setOf("Red"))

    @Test fun rankingNullsExactFiltersAndClassifiedColumns() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE wine_reviews (id INTEGER, country TEXT, province TEXT, variety TEXT, points INTEGER, winery TEXT, review_summary TEXT, name TEXT, body TEXT, tannin TEXT, acidity TEXT)")
            fun insert(
                id: Int,
                points: Int?,
                winery: String,
                country: String = "France",
                body: String = "Full-Bodied",
                tannin: String = "Moderate",
                acidity: String = "Tart",
            ) {
                db.execSQL(
                    "INSERT INTO wine_reviews VALUES (?, ?, 'Bordeaux', 'Merlot', ?, ?, 'a review', 'Merlot', ?, ?, ?)",
                    arrayOf<Any?>(id, country, points, winery, body, tannin, acidity),
                )
            }
            insert(1, null, "A")
            insert(2, 90, "B")
            insert(3, 90, "B")
            insert(4, 91, "Z", body = "Medium-Bodied")
            insert(5, 99, "A", country = "Italy")
            insert(6, 89, "C", tannin = "Smooth")
            fun ids(selection: GuidedCriteria): List<Int> {
                val query = GuidedReviewQuery.from(selection)
                return db.rawQuery(query.sql, query.arguments).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
                }
            }
            assertEquals(listOf(4, 2, 3), ids(criteria))
            val filtered = criteria.copy(body = setOf("Full-Bodied"), tannin = setOf("Moderate"), acidity = setOf("Tart"))
            assertEquals(listOf(2, 3, 1), ids(filtered))
            assertTrue(ids(criteria.copy(body = setOf("Light-Bodied"))).isEmpty())
            // A zero score still ranks ahead of null points regardless of winery.
            db.execSQL("UPDATE wine_reviews SET points=0 WHERE id=3")
            assertEquals(listOf(2, 3, 1), ids(filtered))
        }
    }

    @Test fun allSixTypesAndSparseSelectionsFilterWithoutRequiringGeography() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE wine_reviews (id INTEGER, country TEXT, province TEXT, variety TEXT, points INTEGER, winery TEXT, review_summary TEXT, name TEXT, body TEXT, tannin TEXT, acidity TEXT)")
            val rows = listOf(
                Triple("Red", "Merlot", "Estate Merlot"),
                Triple("White", "Chardonnay", "Estate Chardonnay"),
                Triple("Sparkling", "Champagne Blend", "Brut Champagne"),
                Triple("Rosé", "Rosé", "Estate Rosé"),
                Triple("Sweet", "Riesling", "Late Harvest Riesling"),
                Triple("Fortified", "Port", "Tawny Port"),
            )
            rows.forEachIndexed { index, row ->
                db.execSQL(
                    "INSERT INTO wine_reviews VALUES (?, 'France', 'Bordeaux', ?, 90, 'Estate', 'light-bodied with racy acidity', ?, 'Light-Bodied', 'Moderate', 'Crisp')",
                    arrayOf<Any>(index + 1, row.second, row.third),
                )
            }
            db.execSQL("UPDATE wine_reviews SET review_summary='lusciously sweet wine, light-bodied with racy acidity' WHERE id=5")
            fun ids(criteria: GuidedCriteria): List<Int> {
                val query = GuidedReviewQuery.from(criteria)
                return db.rawQuery(query.sql, query.arguments).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
                }
            }
            assertEquals(listOf(1, 2, 5), ids(GuidedCriteria(wineType = setOf("Red", "White"))))
            assertEquals(listOf(5), ids(GuidedCriteria(wineType = setOf("Red", "White"), sweetness = setOf("Sweet"))))
            assertEquals(listOf(1), ids(GuidedCriteria(wineType = setOf("Red"))))
            assertEquals(listOf(2, 5), ids(GuidedCriteria(wineType = setOf("White"))))
            assertEquals(listOf(3), ids(GuidedCriteria(wineType = setOf("Sparkling"))))
            assertEquals(listOf(4), ids(GuidedCriteria(wineType = setOf("Rosé"))))
            assertEquals(listOf(5), ids(GuidedCriteria(sweetness = setOf("Sweet"))))
            assertEquals(listOf(6), ids(GuidedCriteria(wineType = setOf("Fortified"))))
            assertEquals(listOf(1, 2, 3), ids(GuidedCriteria(country = "France")))
            assertEquals(listOf(1, 2, 3), ids(GuidedCriteria(province = "Bordeaux")))
            assertEquals(listOf(1, 2, 3), ids(GuidedCriteria(body = setOf("Light-Bodied"))))
            assertTrue(ids(GuidedCriteria(body = setOf("Full-Bodied"))).isEmpty())
            assertTrue(ids(GuidedCriteria(wineType = setOf("Red"), country = "Italy")).isEmpty())
        }
    }

    @Test fun gemmaConstraintsEmptyMalformedAndProfileRoundTrip() {
        val response = """{"recommendations":[{"sweetness":"Bone-Dry","wine_type":"Red","country":"France","province":"Bordeaux","variety":"Merlot","body":"Full-Bodied","tannin":"Moderate","acidity":"Soft","flavor_notes":["plum","spice"],"summary":"A model-generated Merlot profile."}]}"""
        val selected = criteria.copy(body = setOf("Full-Bodied"))
        val card = GuidedGemmaResponse.parse(response, selected).single()
        assertTrue(card.profileComplete)
        assertNull(card.rating)
        assertEquals("Unknown", card.winery)
        assertEquals("Unknown", card.reviewSummary)
        assertEquals(card.copy(isFavorite = true), card.toStageWine().toWineSuggestion())
        assertEquals("Bone-Dry", GuidedGemmaResponse.parse(response,
            GuidedCriteria(wineType = setOf("Red", "White"), sweetness = setOf("Bone-Dry", "Off-Dry"))).single().sweetness)
        assertTrue(runCatching { GuidedGemmaResponse.parse(response, GuidedCriteria(sweetness = setOf("Sweet"))) }.isFailure)
        val typeOnly = GuidedGemmaResponse.parse(response, GuidedCriteria(wineType = setOf("Red"))).single()
        assertEquals("Merlot", typeOnly.variety)
        assertEquals("Bordeaux", typeOnly.province)
        assertTrue(GuidedGemmaResponse.parse("""{"recommendations":[]}""", criteria).isEmpty())
        listOf("nonsense", "{}", response.replace("Bordeaux", "Burgundy"),
            response.replace("Full-Bodied", "Light-Bodied"), response.replace("Red", "White")).forEach { invalid ->
            assertTrue(runCatching { GuidedGemmaResponse.parse(invalid, selected) }.isFailure)
        }
    }
}
