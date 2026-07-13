package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSanityFailureTest {
    @Test
    fun collapsedProfileReturnsControlledFailureWithoutThrowing() {
        val result = rejectedProfileSanityResult<Unit>("output collapsed to black")

        assertTrue(result.isFailure)
        assertEquals(
            "Профильная обработка остановлена: результат стал почти полностью чёрным.",
            result.exceptionOrNull()?.message
        )
    }
}
