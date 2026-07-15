package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO

data class Stage6RegressionFixture(
    val name: String,
    val frames: List<ArgbPixelImage>,
    val referenceFrameIndex: Int,
    val referenceStars: List<DetectedStar>
)

object Stage6RegressionFixtureLoader {
    const val LOCAL_DIRECTORY_PROPERTY = "astrophoto.stage6.fixtureDir"

    fun configuredDirectory(): File? = System.getProperty(LOCAL_DIRECTORY_PROPERTY)
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)

    fun load(directory: File): Stage6RegressionFixture {
        val manifestFile = directory.resolve("manifest.properties")
        require(manifestFile.isFile) { "Missing Stage 6 fixture manifest: $manifestFile" }
        val properties = Properties().apply {
            manifestFile.inputStream().use(::load)
        }
        val frameNames = properties.getProperty("frames")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        require(frameNames.size >= 2) { "A Stage 6 fixture needs at least two frames" }
        val frames = frameNames.map { name ->
            val file = directory.resolve(name)
            val decoded = requireNotNull(ImageIO.read(file)) { "Unable to decode fixture frame: $file" }
            val pixels = IntArray(decoded.width * decoded.height) { index ->
                val x = index % decoded.width
                val y = index / decoded.width
                decoded.getRGB(x, y) or 0xFF000000.toInt()
            }
            ArgbPixelImage(decoded.width, decoded.height, pixels)
        }
        require(frames.all { it.width == frames.first().width && it.height == frames.first().height }) {
            "All Stage 6 fixture frames must have identical dimensions"
        }
        val referenceName = properties.getProperty("referenceFrame", frameNames.first())
        val referenceIndex = frameNames.indexOf(referenceName)
        require(referenceIndex >= 0) { "referenceFrame must be present in frames" }
        val starsFile = directory.resolve(
            properties.getProperty("referenceStars", "reference-stars.csv")
        )
        val stars = starsFile.readLines()
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { line ->
                val values = line.split(',').map(String::trim)
                require(values.size >= 2) { "Invalid reference star row: $line" }
                DetectedStar(
                    x = values[0].toFloat(),
                    y = values[1].toFloat(),
                    flux = values.getOrNull(2)?.toFloatOrNull() ?: 500f,
                    localBackground = values.getOrNull(3)?.toFloatOrNull() ?: 20f,
                    localContrast = values.getOrNull(4)?.toFloatOrNull() ?: 60f,
                    width = values.getOrNull(5)?.toFloatOrNull() ?: 1.8f,
                    ellipticity = values.getOrNull(6)?.toFloatOrNull() ?: 0.1f,
                    confidence = values.getOrNull(7)?.toFloatOrNull() ?: 0.95f
                )
            }
            .toList()
        require(stars.isNotEmpty()) { "Stage 6 fixture needs reference stars" }
        return Stage6RegressionFixture(
            properties.getProperty("name", directory.name),
            frames,
            referenceIndex,
            stars
        )
    }
}
