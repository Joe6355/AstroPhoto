package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform
import com.joe6355.astrophoto.processing.jpeg.v2.registration.StarSimilarityRegistrar
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PairedStarRegistrationTest {
    private val registrar = StarSimilarityRegistrar()
    private val transform = ReferenceToSourceTransform(2f, 6f, -0.0032f)

    @Test fun keepsKnownPairsWhenConfidenceRankingsHaveDisjointHypothesisSets() {
        val pairs = pairs()
        val result = register(pairs)
        assertTrue(result.toString(), result.isReliable)
        assertTrue(result.toString(), result.rotationAllowed)
        assertEquals(48, result.inlierStars)
        assertEquals(transform.rotationRadians, result.rotationRadians, 0.00001f)
        assertEquals(transform.dx, result.dx, 0.01f)
        assertEquals(transform.dy, result.dy, 0.01f)
        assertEquals(1f, result.scale, 0f)
    }

    @Test fun excludesUnmatchedPairsWithoutInventingAlternativeCorrespondences() {
        val pairs = pairs().mapIndexed { index, pair ->
            if (index < 12) pair.first to pair.second.copy(x = pair.second.x + 3f,
                y = pair.second.y - index * 1.7f - 2f) else pair
        }
        val result = register(pairs)
        assertTrue(result.toString(), result.isReliable)
        assertEquals(36, result.inlierStars)
        assertEquals(transform.rotationRadians, result.rotationRadians, 0.00001f)
    }

    @Test fun repeatedMeasurementsOfOneStarCannotInflateSupport() {
        assertFalse(register(List(48) { pairs().first() }).isReliable)
    }

    @Test fun incorrectPairLabelsCannotBeRematchedIntoAPassingCatalog() {
        val pairs = pairs()
        val wrong = pairs.indices.map { index -> pairs[index].first to pairs[(index + 1) % pairs.size].second }
        assertFalse(register(wrong).isReliable)
    }

    @Test fun fixedScaleFitRejectsAnActualScaleChange() {
        val wrongScale = pairs().map { (reference, candidate) ->
            reference to candidate.copy(x = candidate.x * 1.04f, y = candidate.y * 1.04f)
        }
        assertFalse(register(wrongScale).isReliable)
    }

    @Test fun concentratedStarsStillCannotAuthorizeRotation() {
        val concentrated = pairs().map { (reference, _) ->
            val star = reference.copy(x = reference.x * 0.1f, y = reference.y * 0.1f)
            val mapped = transform.mapOutputToSource(star.x, star.y)
            star to star.copy(x = mapped.x, y = mapped.y)
        }
        assertFalse(register(concentrated).isReliable)
    }

    @Test fun rotationMustAgreeWithAnExistingMeasuredPrior() {
        assertFalse(registrar.registerPairedRigid(pairs(), 1440, 1920,
            expectedRotationRadians = 0.01f).isReliable)
    }

    @Test fun pureTranslationDoesNotInventRotation() {
        val translated = pairs().map { (reference, candidate) ->
            reference to candidate.copy(x = reference.x + 2f, y = reference.y + 6f)
        }
        val result = register(translated)
        assertFalse(result.toString(), result.rotationAllowed)
        assertEquals("rotation_did_not_materially_improve_fit", result.rotationRejectionReason)
    }

    private fun register(pairs: List<Pair<DetectedStar, DetectedStar>>) =
        registrar.registerPairedRigid(pairs, 1440, 1920)

    private fun pairs(): List<Pair<DetectedStar, DetectedStar>> {
        val random = Random(17072)
        return List(48) { index ->
            val reference = DetectedStar(60f + random.nextFloat() * 1320f,
                60f + random.nextFloat() * 1800f, 100f, 0.01f, 0.8f, 2f, 0.05f,
                0.6f + index * 0.008f)
            val mapped = transform.mapOutputToSource(reference.x, reference.y)
            reference to reference.copy(x = mapped.x, y = mapped.y, confidence = 1f - index * 0.008f)
        }
    }
}
