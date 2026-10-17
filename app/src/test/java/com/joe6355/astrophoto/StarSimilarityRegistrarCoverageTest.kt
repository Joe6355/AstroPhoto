package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.registration.StarSimilarityRegistrar
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class StarSimilarityRegistrarCoverageTest {
    private val registrar = StarSimilarityRegistrar()
    private val width = 1_024
    private val height = 768

    @Test
    fun automaticTranslationAcceptsPerfectMatchesInADenseCatalog() {
        val stars = catalog(240)
        val result = registrar.registerAutomatic(stars, shifted(stars), width, height,
            forceTranslationOnly = true)
        assertTrue(result.toString(), result.isReliable)
        assertEquals(64, result.inlierStars)
        assertEquals(240, result.referenceStars)
        assertEquals(240, result.detectedStars)
        assertEquals(2.25f, result.dx, 0.01f)
        assertEquals(-1.5f, result.dy, 0.01f)
    }

    @Test
    fun automaticRotationAcceptsPerfectMatchesInADenseCatalog() {
        val stars = catalog(240)
        val angle = Math.toRadians(0.4).toFloat()
        val result = registrar.registerAutomatic(stars, shifted(stars, angle), width, height)
        assertTrue(result.toString(), result.isReliable)
        assertTrue(result.toString(), result.rotationAllowed)
        assertEquals(angle, result.rotationRadians, 0.00001f)
        assertEquals(1f, result.scale, 0f)
    }

    @Test
    fun unevaluatedFaintDetectionsDoNotChangeAcceptedEvidenceOrConfidence() {
        val stars = catalog(240)
        val bounded = registrar.registerAutomatic(stars.take(64), shifted(stars.take(64)),
            width, height, forceTranslationOnly = true)
        val dense = registrar.registerAutomatic(stars, shifted(stars), width, height,
            forceTranslationOnly = true)
        assertTrue(dense.toString(), dense.isReliable)
        assertEquals(bounded.dx, dense.dx, 0f)
        assertEquals(bounded.dy, dense.dy, 0f)
        assertEquals(bounded.inlierStars, dense.inlierStars)
        assertEquals(bounded.matchedStars, dense.matchedStars)
        assertEquals(bounded.confidence, dense.confidence, 0f)
    }

    @Test
    fun lowCoverageWithinTheComparedCatalogStillRejects() {
        val stars = catalog(240)
        val random = Random(771)
        val candidate = shifted(stars.take(12)) + stars.drop(12).map {
            it.copy(x = 20f + random.nextFloat() * 980f,
                y = 20f + random.nextFloat() * 720f)
        }
        val result = registrar.registerAutomatic(stars, candidate, width, height,
            forceTranslationOnly = true)
        assertFalse(result.toString(), result.isReliable)
        assertEquals("Star match coverage is too low", result.rejectionReason)
    }

    private fun catalog(count: Int): List<DetectedStar> {
        val random = Random(1701)
        return List(count) { index ->
            DetectedStar(20f + random.nextFloat() * 950f, 20f + random.nextFloat() * 700f,
                flux = 1_000f - index, localBackground = 0.01f,
                localContrast = 0.7f, width = 2f, ellipticity = 0f, confidence = 0.95f)
        }
    }

    private fun shifted(stars: List<DetectedStar>, angle: Float = 0f) = stars.map {
        it.copy(x = cos(angle) * it.x - sin(angle) * it.y + 2.25f,
            y = sin(angle) * it.x + cos(angle) * it.y - 1.5f)
    }
}
