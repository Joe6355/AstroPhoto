package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.analysis.JpegStarDetector
import com.joe6355.astrophoto.processing.jpeg.v2.model.SkyMask
import com.joe6355.astrophoto.processing.jpeg.v2.registration.FullResolutionStarCentroidDetector
import com.joe6355.astrophoto.processing.jpeg.v2.registration.StarSimilarityRegistrar
import com.joe6355.astrophoto.processing.jpeg.v2.registration.StellarCentroidFrameRefiner
import com.joe6355.astrophoto.processing.jpeg.v2.registration.FullResolutionStarPatch
import com.joe6355.astrophoto.processing.jpeg.v2.registration.TemporalMotionCluster
import com.joe6355.astrophoto.processing.jpeg.v2.registration.StellarCentroidRefinementPolicy
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only diagnosis without the temporal model or downsampled frame selection. Not an Android test. */
class LocalFullResolutionCorrespondenceAuditTest {
    @Test fun checksIndependentFullResolutionCorrespondences() {
        val path = System.getenv("ASTROPHOTO_CORRESPONDENCE_AUDIT_ROOT")
        assumeTrue("Local backed-up sessions required", !path.isNullOrBlank())
        val root = File(requireNotNull(path))
        val sessions = listOf("Session_20260901_013659_дорога_20", "Session_20260828_220247_дом100",
            "Session_20260828_231111", "Session_20260829_001615", "Session_20260813_001021_дорога_20")
        for (session in sessions) {
            val inputs = File(root, "$session/Lights/JPEG").listFiles().orEmpty()
                .filter { it.extension.equals("jpg", true) }.sortedBy { it.name }
            assertTrue("Missing local session: $session", inputs.size >= 10)
            val sample = (0..9).map { inputs[it * (inputs.size - 1) / 9] }
            fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
            val before = sample.associateWith(::hash)
            var width = 0; var height = 0
            val frames = sample.map { file ->
                val decoded = requireNotNull(ImageIO.read(file))
                width = decoded.width; height = decoded.height
                val pixels = decoded.getRGB(0, 0, width, height, null, 0, width)
                val image = ArgbPixelImage(width, height, pixels)
                val source = IntArrayPixelSource(width, height, pixels)
                val stars = JpegStarDetector().detect(image, SkyMask.full(width, height)).stars
                val verified = stars.mapNotNull { star ->
                    FullResolutionStarCentroidDetector().detect(source, star.x, star.y, 2f).measurement?.let {
                        star.copy(x = it.x, y = it.y, width = (it.fwhmX + it.fwhmY) * 0.5f,
                            ellipticity = it.ellipticity, confidence = it.confidence)
                    }
                }
                println("FULL_CORRESPONDENCE session=$session frame=${file.name} stars=${stars.size} compact=${verified.size}")
                verified
            }
            val reference = frames.indices.maxBy { frames[it].size }
            var reliable = 1
            var centroidVerified = 1
            var identityVerified = 1
            fun source(file: File): IntArrayPixelSource {
                val decoded = requireNotNull(ImageIO.read(file))
                return IntArrayPixelSource(decoded.width, decoded.height,
                    decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width))
            }
            val referenceSource = source(sample[reference])
            val patches = frames[reference].map { star -> FullResolutionStarPatch(
                x = star.x, y = star.y, confidence = star.confidence, localContrast = star.localContrast,
                width = star.width, ellipticity = star.ellipticity,
                sector = (star.y / height * 3).toInt().coerceIn(0, 2) * 3 +
                    (star.x / width * 3).toInt().coerceIn(0, 2),
                motionCluster = TemporalMotionCluster.UNSTABLE_OR_UNKNOWN, skyCoverage = 1f
            ) }
            frames.indices.filter { it != reference }.forEach { index ->
                val result = StarSimilarityRegistrar().registerAutomatic(frames[reference], frames[index], width, height)
                if (result.isReliable) reliable++
                if (result.isReliable) {
                    val refined = StellarCentroidFrameRefiner().refine(
                        frameId = sample[index].name, isReference = false, reference = referenceSource,
                        candidate = source(sample[index]), initialTransform = result.referenceToSourceTransform(),
                        znccTransform = result.referenceToSourceTransform(), patches = patches,
                        analysisScaleUncertainty = 0f, stage10Residual = result.residualError,
                        sequenceResidual = 0f, stage10Confidence = result.confidence)
                    if (refined.accepted) centroidVerified++
                    val identity = StellarCentroidRefinementPolicy().decideVerifiedIdentity(refined.verification)
                    if (identity.accepted) identityVerified++
                    println("FULL_CENTROID session=$session candidate=${sample[index].name} " +
                        "accepted=${refined.accepted} matches=${refined.acceptedStarCount} " +
                        "median=${refined.medianResidual} p90=${refined.percentile90Residual} reason=${refined.rejectionReason} " +
                        "identityAccepted=${identity.accepted} identityReason=${identity.rejectionReason}")
                }
                println("FULL_CORRESPONDENCE session=$session reference=${sample[reference].name} candidate=${sample[index].name} " +
                    "reliable=${result.isReliable} inliers=${result.inlierStars} residual=${result.residualError} " +
                    "dx=${result.dx} dy=${result.dy} rotation=${result.rotationRadians} reason=${result.rejectionReason}")
            }
            println("FULL_CORRESPONDENCE_SUMMARY session=$session sampled=${sample.size}/${inputs.size} reliable=$reliable centroidVerified=$centroidVerified identityVerified=$identityVerified")
            assertEquals("Original copies must remain unchanged", before, sample.associateWith(::hash))
        }
    }
}
