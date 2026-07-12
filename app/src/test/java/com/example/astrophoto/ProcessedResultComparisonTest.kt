package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessedResultComparisonTest {
    @Test
    fun comparisonResolvesBothSidesThroughSharedLookup() {
        val path = "Pictures/AstroPhoto/S/Processed/"
        val lookup = FakeLookup().apply {
            byName["First.jpg" to path] = ProviderImageRecord("content://files/1", "First.jpg", path)
            byName["Second.jpg" to path] = ProviderImageRecord("content://files/2", "Second.jpg", path)
        }
        val pair = resolveImagePair(
            ImageSourceReference("First.jpg", path, null, null),
            ImageSourceReference("Second.jpg", path, null, null),
            lookup
        )
        assertEquals("content://files/1", pair?.first?.pathOrUri)
        assertEquals("content://files/2", pair?.second?.pathOrUri)
    }

    @Test
    fun comparisonFailsWhenEitherSideIsUnavailable() {
        val path = "Pictures/AstroPhoto/S/Processed/"
        val lookup = FakeLookup().apply {
            byName["First.jpg" to path] = ProviderImageRecord("content://files/1", "First.jpg", path)
        }
        assertNull(
            resolveImagePair(
                ImageSourceReference("First.jpg", path, null, null),
                ImageSourceReference("Missing.jpg", path, null, null),
                lookup
            )
        )
    }
}
