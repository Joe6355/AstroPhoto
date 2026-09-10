package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.joe6355.astrophoto.processing.jpeg.v2.analysis.ReferenceFrameSelector
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactAnalyzer
import com.joe6355.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator
import com.joe6355.astrophoto.processing.jpeg.v2.registration.SequenceAwareRegistrationEngine
import com.joe6355.astrophoto.processing.jpeg.v2.registration.TemporalFeatureFrame
import com.joe6355.astrophoto.processing.jpeg.v2.registration.registerWithReferenceRecovery
import kotlinx.coroutines.runBlocking
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, read-only diagnosis on a local backup. ImageIO decoding is not an Android device test. */
class LocalSessionRegistrationAuditTest {
    @Test fun reportsAnalysisAndRegistrationOnLocalJpegCopyWithoutChangingInputs() = runBlocking {
        val path = System.getenv("ASTROPHOTO_QA_SESSION_DIR")
        assumeTrue("Set ASTROPHOTO_QA_SESSION_DIR to a local Lights/JPEG backup", !path.isNullOrBlank())
        val inputs = File(requireNotNull(path)).listFiles().orEmpty()
            .filter { it.extension.equals("jpg", true) || it.extension.equals("jpeg", true) }
            .sortedBy { it.name }.take(10)
        assertEquals("The diagnostic needs at least ten JPEGs", 10, inputs.size)
        fun fingerprint(file: File) = MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val before = inputs.associateWith(::fingerprint)
        val estimator = SkyMaskEstimator()
        val analyzer = JpegFrameAnalyzer()
        val raw = inputs.map { file ->
            val image = decodeSample(file)
            analyzer.analyze(file.name, file.name, image, estimator.estimate(image))
        }
        val artifacts = StaticArtifactAnalyzer()
        val mask = artifacts.analyze(raw.map { ArtifactFrameObservation(it.id, it.stars) },
            raw.first().width, raw.first().height)
        val filtered = raw.map { artifacts.excludeFrom(it, mask) }
        val indices = inputs.mapIndexed { index, file -> file.name to index + 1 }.toMap()
        val selector = ReferenceFrameSelector()
        val eligible = selector.selectForIntegration(filtered, indices, 30).analyses
        val reference = selector.select(eligible).analysis
        println("LOCAL_QA decoder=ImageIO sample=${reference.width}x${reference.height} " +
            "reference=${reference.id} eligible=${eligible.size} staticArtifacts=${mask.regions.size}")
        raw.zip(filtered).forEach { (beforeFilter, analysis) ->
            println("LOCAL_QA frame=${analysis.id} stars=${beforeFilter.reliableStarCount}->${analysis.reliableStarCount} " +
                "width=${analysis.medianStarWidth} ellipticity=${analysis.medianStarEllipticity} " +
                "snr=${analysis.medianStarSnr} background=${analysis.backgroundLevel} " +
                "mask=${analysis.skyMaskConfidence} invalid=${analysis.hardInvalidReason} hash=${before.getValue(inputs.first { it.name == analysis.id })}")
        }
        val registration = SequenceAwareRegistrationEngine().register(
            eligible.map { TemporalFeatureFrame(it.id, indices.getValue(it.id), it.stars) },
            reference.id, reference.width, reference.height)
        println("LOCAL_QA accepted=${registration.registrations.values.count { it.isReliable }}/${eligible.size} " +
            "motion=${registration.model.selectedMotionModel} observable=${registration.model.motionObservable} " +
            "velocity=${registration.model.velocityX},${registration.model.velocityY} score=${registration.model.score}")
        registration.registrations.forEach { (id, result) ->
            println("LOCAL_QA frame=$id accepted=${result.isReliable} matches=${result.matchedStars} " +
                "inliers=${result.inlierStars} residual=${result.residualError} confidence=${result.confidence} " +
                "reason=${result.rejectionReason} path=${registration.frameAcceptancePaths[id]}")
        }
        // Diagnose reference bias without changing the production selector or acceptance thresholds.
        eligible.filter { it.id != reference.id && it.reliableStarCount >= 4 }.forEach { alternate ->
            val replay = SequenceAwareRegistrationEngine().register(
                eligible.map { TemporalFeatureFrame(it.id, indices.getValue(it.id), it.stars) },
                alternate.id, alternate.width, alternate.height)
            println("LOCAL_QA alternateReference=${alternate.id} " +
                "accepted=${replay.registrations.values.count { it.isReliable }}/${eligible.size} " +
                "observable=${replay.model.motionObservable}")
        }
        val recovered = registerWithReferenceRecovery(eligible, indices, reference.id, 4) { key ->
            SequenceAwareRegistrationEngine().register(
                eligible.map { TemporalFeatureFrame(it.id, indices.getValue(it.id), it.stars) },
                key, reference.width, reference.height)
        }
        println("LOCAL_QA recoveryReferenceIndex=${recovered.referenceCaptureIndex} " +
            "accepted=${recovered.registrations.values.count { it.isReliable }}/${eligible.size}")
        assertTrue(recovered.registrations.values.count { it.isReliable } >=
            registration.registrations.values.count { it.isReliable })
        assertEquals(before, inputs.associateWith(::fingerprint))
    }

    private fun decodeSample(file: File): ArgbPixelImage {
        val original = requireNotNull(ImageIO.read(file)) { "Cannot decode ${file.name}" }
        val scale = minOf(1.0, 960.0 / maxOf(original.width, original.height))
        val sample = BufferedImage((original.width * scale).roundToInt(),
            (original.height * scale).roundToInt(), BufferedImage.TYPE_INT_RGB)
        val graphics = sample.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(original, 0, 0, sample.width, sample.height, null)
        } finally {
            graphics.dispose()
            original.flush()
        }
        return ArgbPixelImage(sample.width, sample.height,
            sample.getRGB(0, 0, sample.width, sample.height, null, 0, sample.width)).also { sample.flush() }
    }
}
