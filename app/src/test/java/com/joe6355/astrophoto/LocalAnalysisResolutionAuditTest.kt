package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.analysis.JpegFrameAnalyzer
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.ArtifactFrameObservation
import com.joe6355.astrophoto.processing.jpeg.v2.artifacts.StaticArtifactAnalyzer
import com.joe6355.astrophoto.processing.jpeg.v2.masking.SkyMaskEstimator
import com.joe6355.astrophoto.processing.jpeg.v2.registration.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only diagnostic. ImageIO resizing is not evidence of Android decoder equivalence. */
class LocalAnalysisResolutionAuditTest {
    @Test fun compareAnalysisCatalogsAndRegistrationAtSeveralResolutions() {
        val input = System.getenv("ASTROPHOTO_RESOLUTION_AUDIT_INPUTS")
        assumeTrue("Set an explicit directory of copied JPEG inputs", !input.isNullOrBlank())
        val files = File(requireNotNull(input)).listFiles().orEmpty()
            .filter { it.extension.equals("jpg", true) }.sortedBy { it.name }
        require(files.size >= 4)
        val before = files.associateWith(::sha256)
        val referenceIndex = (System.getenv("ASTROPHOTO_RESOLUTION_REFERENCE")?.toInt() ?: 5) - 1
        require(referenceIndex in files.indices)
        val times = System.getenv("ASTROPHOTO_RESOLUTION_TIMING_CSV")?.let { path ->
            File(path).readLines().filter { it.isNotBlank() }.associate { line ->
                val parts = line.split(',', limit = 2)
                parts[0] to parts[1].toFloat()
            }
        }.orEmpty()
        val maxima = System.getenv("ASTROPHOTO_RESOLUTION_MAXIMA")?.split(',')?.map(String::toInt)
            ?: listOf(960, 1280, 1920)
        for (maximum in maxima) {
            val started = System.nanoTime()
            val analyses = files.map { file ->
                val decoded = requireNotNull(ImageIO.read(file))
                val scale = minOf(1.0, maximum.toDouble() / maxOf(decoded.width, decoded.height))
                val width = (decoded.width * scale).roundToInt()
                val height = (decoded.height * scale).roundToInt()
                val resized = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                val graphics = resized.createGraphics()
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.drawImage(decoded, 0, 0, width, height, null)
                graphics.dispose()
                val image = ArgbPixelImage(width, height, resized.getRGB(0, 0, width, height, null, 0, width))
                resized.flush(); decoded.flush()
                JpegFrameAnalyzer().analyze(file.name, file.name, image, SkyMaskEstimator().estimate(image))
            }
            val width = analyses.first().width
            val height = analyses.first().height
            val staticAnalyzer = StaticArtifactAnalyzer()
            val mask = staticAnalyzer.analyze(analyses.map { ArtifactFrameObservation(it.id, it.stars) }, width, height)
            val filtered = analyses.map { staticAnalyzer.excludeFrom(it, mask) }
            val features = filtered.mapIndexed { index, analysis -> TemporalFeatureFrame(analysis.id,
                index + 1, analysis.stars, times[analysis.id]) }
            val diagnostics = SequenceAwareRegistrationEngine().register(features,
                files[referenceIndex].name, width, height)
            val model = diagnostics.model
            val validated = TransformSequenceValidator().validate(features.map { frame -> OrderedRegistration(
                frame.frameId, frame.captureIndex, frame.frameId == files[referenceIndex].name,
                diagnostics.registrations.getValue(frame.frameId)) },
                ExpectedSequenceMotionModel(model.velocityX, model.velocityY, model.referenceIndex,
                    model.residual, model.motionObservable, diagnostics.verification.selectedModel.score,
                    model.timeCoordinateByCaptureIndex),
                com.joe6355.astrophoto.processing.jpeg.v2.model.TransformPoint(width * 0.5f, height * 0.5f))
            println("RESOLUTION_DIAGNOSTIC maximum=$maximum size=${width}x$height " +
                "reference=${files[referenceIndex].name} rawStars=${analyses.map { it.reliableStarCount }} " +
                "filteredStars=${filtered.map { it.reliableStarCount }} " +
                "movingTracks=${diagnostics.trackAnalysis.movingTrackCount} observable=${model.motionObservable} " +
                "accepted=${validated.registrations.count { it.registration.isReliable }} " +
                "seconds=${(System.nanoTime() - started) / 1_000_000_000.0}")
            validated.registrations.forEach { entry ->
                val result = entry.registration
                println("RESOLUTION_FRAME maximum=$maximum frame=${entry.frameId} accepted=${result.isReliable} " +
                    "reason=${result.rejectionReason} dx=${result.dx} dy=${result.dy} angle=${result.rotationRadians} " +
                    "model=${result.registrationModel} matches=${result.matchedStars} inliers=${result.inlierStars}")
            }
        }
        assertEquals("Copied JPEG inputs must remain unchanged", before, files.associateWith(::sha256))
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
