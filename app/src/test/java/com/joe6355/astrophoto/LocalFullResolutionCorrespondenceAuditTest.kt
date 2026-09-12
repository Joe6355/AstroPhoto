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
import java.awt.image.BufferedImage
import com.joe6355.astrophoto.processing.jpeg.v2.integration.LinearWeightedIntegrator
import com.joe6355.astrophoto.processing.jpeg.v2.integration.WeightedIntegrationFrame
import com.joe6355.astrophoto.processing.jpeg.v2.model.RegistrationResult
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.color.LinearRgb16
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.joe6355.astrophoto.processing.jpeg.v2.quality.LineArtifactDetector
import kotlinx.coroutines.runBlocking
import kotlin.math.sqrt
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
        val onlySession = System.getenv("ASTROPHOTO_CORRESPONDENCE_SESSION")
        for (session in sessions.filter { onlySession == null || it == onlySession }) {
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
            val identityFrames = mutableListOf(sample[reference])
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
                    if (identity.accepted) {
                        identityVerified++
                        identityFrames += sample[index]
                    }
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
            System.getenv("ASTROPHOTO_STACK_BENEFIT_DIR")?.let { output ->
                require(onlySession == session && identityFrames.size >= 4)
                compareDiagnosticStack(identityFrames, referenceSource, frames[reference], File(output), ::source)
            }
            assertEquals("Original copies must remain unchanged", before, sample.associateWith(::hash))
        }
    }

    /** Experimental identity stack only: no registration-policy override is installed in the app. */
    private fun compareDiagnosticStack(files: List<File>, reference: IntArrayPixelSource,
        stars: List<DetectedStar>, output: File, decode: (File) -> IntArrayPixelSource) = runBlocking {
        require(!output.exists()) { "Use a fresh experiment directory; do not overwrite results" }
        require(output.mkdirs())
        val width = reference.width; val height = reference.height
        val pixels = LongArray(width * height)
        val identity = RegistrationResult(0f, 0f, 0f, 1f, stars.size, stars.size,
            stars.size, 0f, 1f, true, null, registrationModel = "DIAGNOSTIC_VERIFIED_IDENTITY")
        LinearWeightedIntegrator().integrate(width, height,
            files.map { WeightedIntegrationFrame(it.name, it, identity, 1f / files.size) },
            maximumWorkingMemoryBytes = 128L * 1024 * 1024, openSource = decode,
            allowRobustClipping = false, writeLinearTile = { tile, values ->
                for (y in 0 until tile.height) values.copyInto(pixels,
                    (tile.top + y) * width + tile.left, y * tile.width, (y + 1) * tile.width)
            })
        val before = ArgbPixelImage(width, height, IntArray(width * height) { reference.argbAt(it % width, it / width) })
        val after = ArgbPixelImage(width, height, IntArray(pixels.size) { LinearRgb16.toArgb(pixels[it]) })
        val retention = ReferenceStarRetentionValidator().validate(before, after, stars)
        val artifacts = LineArtifactDetector().compareStarNeighborhoods(reference,
            IntArrayPixelSource(width, height, after.pixels), stars)
        val excluded = BooleanArray(pixels.size)
        // Same support for both images. Exclude all detected bright structures and known star windows.
        val union = stars + JpegStarDetector().detect(after, SkyMask.full(width, height)).stars
        for (star in union) for (dy in -12..12) for (dx in -12..12) {
            val x = star.x.toInt() + dx; val y = star.y.toInt() + dy
            if (x in 0 until width && y in 0 until height) excluded[y * width + x] = true
        }
        fun backgroundMetrics(value: (Int) -> Float): String {
            var sum = 0.0; var squares = 0.0; var count = 0; var zero = 0
            for (y in 1 until height - 1 step 2) for (x in 1 until width - 1 step 2) {
                val i = y * width + x
                if (excluded[i] || excluded[i - 1] || excluded[i + 1] ||
                    excluded[i - width] || excluded[i + width]) continue
                val center = value(i)
                val highPass = center - (value(i - 1) + value(i + 1) + value(i - width) + value(i + width)) * 0.25f
                sum += center; squares += highPass * highPass; count++
                if (center == 0f) zero++
            }
            return "samples=$count meanLinear=${sum/count} highPassRmsLinear=${sqrt(squares/count)} zeroFraction=${zero.toDouble()/count}"
        }
        fun save(name: String, image: ArgbPixelImage, gain: Int) {
            val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val display = image.pixels.map { p ->
                val r = ((p ushr 16 and 255) * gain).coerceAtMost(255)
                val g = ((p ushr 8 and 255) * gain).coerceAtMost(255)
                val b = ((p and 255) * gain).coerceAtMost(255)
                (r shl 16) or (g shl 8) or b
            }.toIntArray()
            bitmap.setRGB(0, 0, width, height, display, 0, width)
            check(ImageIO.write(bitmap, "png", File(output, name)))
        }
        save("reference.png", before, 1); save("stack.png", after, 1)
        save("reference-x8.png", before, 8); save("stack-x8.png", after, 8)
        println("STACK_BENEFIT reference=${files.first().name} frames=${files.map { it.name }}")
        println("STACK_BENEFIT before ${backgroundMetrics { LinearRgb16.luminance(LinearRgb16.fromArgb(before.pixels[it])) }}")
        println("STACK_BENEFIT after ${backgroundMetrics { LinearRgb16.luminance(pixels[it]) }}")
        println("STACK_BENEFIT retention=${retention.metrics} accepted=${retention.accepted} reasons=${retention.hardFailureReasons}")
        println("STACK_BENEFIT artifacts=$artifacts")
        println("STACK_BENEFIT stars=${stars.map { "${it.x},${it.y}" }}")
    }
}
