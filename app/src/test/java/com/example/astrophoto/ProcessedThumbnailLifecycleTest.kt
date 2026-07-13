package com.example.astrophoto

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessedThumbnailLifecycleTest {
    @Test
    fun oldEffectDisposesCapturedResourceNotCurrentState() {
        val first = Any()
        val second = Any()
        var current = first
        var disposed: Any? = null
        val disposeOldEffect = disposeCapturedResource(current) { disposed = it }

        current = second
        disposeOldEffect()

        assertSame(first, disposed)
        assertSame(second, current)
    }

    @Test
    fun loadingSecondComparisonImageDoesNotDisposeFirstImage() {
        val first = Any()
        val disposed = mutableListOf<Any>()
        val disposeFirst = disposeCapturedResource(first, disposed::add)
        val disposePreviousSecond = disposeCapturedResource(null, disposed::add)

        disposePreviousSecond()

        assertTrue(disposed.isEmpty())
        disposeFirst()
        assertSame(first, disposed.single())
    }
}
