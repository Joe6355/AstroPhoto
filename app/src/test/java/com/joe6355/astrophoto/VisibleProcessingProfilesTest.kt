package com.joe6355.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleProcessingProfilesTest {
    @Test
    fun `only validated automatic processing is shown`() {
        assertEquals(
            listOf(AstroProcessingProfile.DEEP_SKY),
            USER_VISIBLE_PROCESSING_PROFILES
        )
    }

    @Test
    fun `legacy processing profiles remain readable`() {
        val profileNames = AstroProcessingProfile.entries.map { it.name }

        assertTrue("DEEP_SKY_ALIGNED" in profileNames)
        assertTrue("URBAN_SKY" in profileNames)
        assertTrue("URBAN_SKY_STRONG" in profileNames)
        assertTrue("MAX_STARS" in profileNames)
        assertTrue("EXPERIMENTAL_STARS" in profileNames)
    }
}
