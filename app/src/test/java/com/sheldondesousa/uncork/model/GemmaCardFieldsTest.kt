package com.sheldondesousa.uncork.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GemmaCardFieldsTest {
    @Test
    fun canonicalAndAliasKeysResolveToAppFieldNames() {
        assertEquals("wine_type", GemmaCardFields.canonicalName("wine_type"))
        assertEquals("wine_type", GemmaCardFields.canonicalName("type"))
        assertEquals("wine_type", GemmaCardFields.canonicalName("wineType"))
        assertEquals("province", GemmaCardFields.canonicalName("wine region"))
        assertEquals("variety", GemmaCardFields.canonicalName("grape_variety"))
        assertEquals("flavor", GemmaCardFields.canonicalName("flavourProfile"))
        assertEquals("suggested_pairing", GemmaCardFields.canonicalName("foodPairing"))
        assertNull(GemmaCardFields.canonicalName("unsupported_field"))
    }

    @Test
    fun everySupportedFieldRecognisesItsCanonicalName() {
        GemmaCardFields.canonicalNames.forEach { canonical ->
            assertEquals(canonical, GemmaCardFields.canonicalName(canonical))
        }
    }
}
