package com.joe6355.astrophoto

import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.quality.LineArtifactDetector
import com.joe6355.astrophoto.processing.jpeg.v2.sampling.IntArrayPixelSource
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only, opt-in regression against private local decoded images; no fixtures are uploaded. */
class LocalStellarArtifactAuditTest {
    @Test fun comparesOriginalAndCorrectedResults() {
        val root = System.getenv("ASTROPHOTO_ARTIFACT_AUDIT_ROOT")
        assumeTrue("Local photo artifacts required", !root.isNullOrBlank())
        val base = File(requireNotNull(root))
        val sources = listOf(
            "AstroSeries_20260813_000139_013.jpg",
            "AstroSeries_20260813_001030_029.jpg",
            "AstroSeries_20260813_000705_001.jpg",
            "AstroSeries_20260813_003100_003.jpg"
        )
        val failures = mutableListOf<String>()
        for ((index, source) in sources.withIndex()) {
            // Locate by unique captured filename: folder names may differ in copied QA datasets.
            val referenceFile = File(base, "device-qa-20260908/sessions").walkTopDown()
                .first { it.name == source }
            fun decode(file: File): ArgbPixelImage {
                val image = requireNotNull(ImageIO.read(file))
                return ArgbPixelImage(image.width, image.height,
                    image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
            }
            val reference = decode(referenceFile)
            val report = File(base, "device-qa-20260910-residual/Session_QA_RigidConsensus_20260910_$index")
                .listFiles()!!.first { it.extension == "json" }.readText()
            val stars = Regex("\"referenceX\":\\s*([\\d.]+),\\s*\"referenceY\":\\s*([\\d.]+),[\\s\\S]*?\"expectedWidth\":\\s*([\\d.]+)")
                .findAll(report).map { m -> DetectedStar(m.groupValues[1].toFloat(), m.groupValues[2].toFloat(),
                    1f, 0f, 1f, m.groupValues[3].toFloat(), 0f, 1f) }
                .distinctBy { it.x to it.y }.toList()
            assertTrue("Actual pipeline reference stars required", stars.isNotEmpty())
            fun compare(file: File) = decode(file).let { candidate ->
                LineArtifactDetector { println("COMPONENT case=$index file=${file.name} $it") }.compareStarNeighborhoods(
                    IntArrayPixelSource(reference.width, reference.height, reference.pixels),
                    IntArrayPixelSource(candidate.width, candidate.height, candidate.pixels), stars)
            }
            val corrected = File(base, "device-qa-20260910-residual/Session_QA_RigidConsensus_20260910_$index/Processed")
                .listFiles()!!.first { it.extension == "png" && !it.name.startsWith("Recovered") }
            val fixed = compare(corrected)
            if (index < 2) {
                val oldFile = File(base, "device-qa-20260910-full/Session_QA_Full_20260910_130308_0${index + 1}")
                    .walkTopDown().first { it.extension == "png" && it.name.startsWith("DeepSky_") }
                val old = compare(oldFile)
                println("STELLAR_AUDIT case=$index stars=${stars.size} old=$old")
                if (old.accepted) failures += "Missed old artifact case $index"
            }
            println("STELLAR_AUDIT case=$index stars=${stars.size} corrected=$fixed")
            if (!fixed.accepted) failures += "Rejected corrected case $index: $fixed"
        }
        assertTrue(failures.joinToString(), failures.isEmpty())
    }
}
