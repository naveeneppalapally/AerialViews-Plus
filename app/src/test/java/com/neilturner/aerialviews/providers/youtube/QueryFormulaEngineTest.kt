package com.neilturner.aerialviews.providers.youtube

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Query Formula Engine Tests")
internal class QueryFormulaEngineTest {
    @Test
    @DisplayName("Should sanitize fast-motion query terms")
    fun testSanitizeFastMotionQueryTerms() {
        val sanitized =
            QueryFormulaEngine.sanitizeQueryForAmbientPlaybackForTest(
                "4k fpv canyon flythrough timelapse",
            ).lowercase()

        assertFalse(sanitized.contains("fpv"))
        assertFalse(sanitized.contains("flythrough"))
        assertFalse(sanitized.contains("timelapse"))
    }

    @Test
    @DisplayName("Generated query pool should avoid fast-motion terms")
    fun testGeneratedQueryPoolAvoidsFastMotionTerms() {        val queries =
            QueryFormulaEngine.generateQueryPool(
                count = 20,
                prefs =
                    QueryFormulaEngine.CategoryPreferences(
                        categoryNature = true,
                        categoryAnimals = false,
                        categoryDrone = true,
                        categoryCities = false,
                        categorySpace = false,
                        categoryOcean = true,
                        categoryWeather = false,
                        categoryWinter = false,
                    ),
                entropySeed = 1234L,
            )

        queries.forEach { query ->
            val normalized = query.lowercase()
            assertFalse(normalized.contains("timelapse"))
            assertFalse(normalized.contains("fpv"))
            assertFalse(normalized.contains("flythrough"))
        }
    }

    @Test
    @DisplayName("Ring pool should not repeat queries until a category wraps")
    fun testRingPoolZeroOverlapUntilWrap() {
        // SPACE matrix is 72 queries; drawing 9 per refresh gives 8
        // strictly disjoint pools before the ring wraps.
        val prefs =
            QueryFormulaEngine.CategoryPreferences(
                categoryNature = false,
                categoryAnimals = false,
                categoryDrone = false,
                categoryCities = false,
                categorySpace = true,
                categoryOcean = false,
                categoryWeather = false,
                categoryWinter = false,
            )
        val sharedPreferences = InMemorySharedPreferences(mutableMapOf())

        val pools =
            List(8) {
                QueryFormulaEngine.generateRingQueryPool(
                    count = 9,
                    prefs = prefs,
                    sharedPreferences = sharedPreferences,
                )
            }

        pools.forEach { assertEquals(9, it.size) }
        for (i in pools.indices) {
            for (j in i + 1 until pools.size) {
                assertTrue(
                    pools[i].intersect(pools[j].toSet()).isEmpty(),
                    "Ring pools $i and $j overlap before wrap",
                )
            }
        }
        val wrapped =
            QueryFormulaEngine.generateRingQueryPool(
                count = 9,
                prefs = prefs,
                sharedPreferences = sharedPreferences,
            )
        assertEquals(pools[0].toSet(), wrapped.toSet())
    }

    @Test
    @DisplayName("Ring pool should avoid fast-motion terms after sanitization")
    fun testRingPoolAvoidsFastMotionTerms() {
        val sharedPreferences = InMemorySharedPreferences(mutableMapOf())
        val queries =
            QueryFormulaEngine.generateRingQueryPool(
                count = 25,
                prefs = QueryFormulaEngine.CategoryPreferences(),
                sharedPreferences = sharedPreferences,
            )

        assertEquals(25, queries.size)
        queries.forEach { query ->
            val normalized = query.lowercase()
            assertFalse(normalized.contains("timelapse"), query)
            assertFalse(normalized.contains("fpv"), query)
            assertFalse(normalized.contains("flythrough"), query)
        }
    }

    @Test
    @DisplayName("Ring cursor should survive pool changes via modulo")
    fun testRingCursorModuloOnPoolChange() {
        val prefs =
            QueryFormulaEngine.CategoryPreferences(
                categoryNature = true,
                categoryAnimals = false,
                categoryDrone = false,
                categoryCities = false,
                categorySpace = false,
                categoryOcean = false,
                categoryWeather = false,
                categoryWinter = false,
            )
        val sharedPreferences = InMemorySharedPreferences(mutableMapOf())
        sharedPreferences.edit().putInt("yt_query_cursor_nature", 10_000).apply()

        val queries =
            QueryFormulaEngine.generateRingQueryPool(
                count = 3,
                prefs = prefs,
                sharedPreferences = sharedPreferences,
            )

        assertEquals(3, queries.size)
    }

    @Test
    @DisplayName("Generated queries should include negative exclusion terms")
    fun testGeneratedQueriesIncludeNegativeExclusionTerms() {
        val sharedPreferences = InMemorySharedPreferences(mutableMapOf())
        val queries =
            QueryFormulaEngine.generateRingQueryPool(
                count = 10,
                prefs = QueryFormulaEngine.CategoryPreferences(),
                sharedPreferences = sharedPreferences,
            )

        assertTrue(queries.isNotEmpty())
        queries.forEach { query ->
            assertTrue(query.contains("-vlog"), "Query should contain -vlog: $query")
            assertTrue(query.contains("-review"), "Query should contain -review: $query")
            assertTrue(query.contains("-talking"), "Query should contain -talking: $query")
        }
    }

    @Test
    @DisplayName("Drone queries should include gear waste negative terms")
    fun testDroneQueriesIncludeGearWasteNegatives() {
        val sharedPreferences = InMemorySharedPreferences(mutableMapOf())
        val prefs =
            QueryFormulaEngine.CategoryPreferences(
                categoryNature = false,
                categoryAnimals = false,
                categoryDrone = true,
                categoryCities = false,
                categorySpace = false,
                categoryOcean = false,
                categoryWeather = false,
                categoryWinter = false,
            )
        val queries =
            QueryFormulaEngine.generateRingQueryPool(
                count = 5,
                prefs = prefs,
                sharedPreferences = sharedPreferences,
            )

        queries.forEach { query ->
            assertTrue(query.contains("-test"), "Drone query should contain -test: $query")
            assertTrue(query.contains("-tutorial"), "Drone query should contain -tutorial: $query")
            assertTrue(query.contains("-lut"), "Drone query should contain -lut: $query")
        }
    }

    @Test
    @DisplayName("getSpForCategory should return appropriate protobuf parameters")
    fun testGetSpForCategory() {
        assertEquals(QueryFormulaEngine.SP_VIDEO_4K_MEDIUM, QueryFormulaEngine.getSpForCategory(QueryFormulaEngine.ContentCategory.DRONE))
        assertEquals(QueryFormulaEngine.SP_VIDEO_4K_MEDIUM, QueryFormulaEngine.getSpForCategory(QueryFormulaEngine.ContentCategory.NATURE))
        assertEquals(QueryFormulaEngine.SP_VIDEO_4K_LONG, QueryFormulaEngine.getSpForCategory(QueryFormulaEngine.ContentCategory.SPACE))
        assertEquals(QueryFormulaEngine.SP_VIDEO_4K_LONG, QueryFormulaEngine.getSpForCategory(QueryFormulaEngine.ContentCategory.WEATHER))
    }

    @Test
    @DisplayName("categoryForQuery should resolve correctly with or without negative terms")
    fun testCategoryForQueryWithAndWithoutNegatives() {
        val droneWithNeg = "4k drone landscape geirangerfjord cinematic 4k -vlog -review -test"
        val droneWithoutNeg = "4k drone landscape geirangerfjord cinematic 4k"

        assertEquals(QueryFormulaEngine.ContentCategory.DRONE, QueryFormulaEngine.categoryForQuery(droneWithNeg))
        assertEquals(QueryFormulaEngine.ContentCategory.DRONE, QueryFormulaEngine.categoryForQuery(droneWithoutNeg))
    }
}