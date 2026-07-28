package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO

enum class ProvisionalSourceClass {
    STAR,
    SENSOR_DEFECT,
    UNCERTAIN
}

enum class ProvisionalCoordinateSpace {
    SKY,
    CAMERA,
    UNKNOWN
}

data class ProvisionalSourceLabel(
    val id: String,
    val classification: ProvisionalSourceClass,
    val x: Float,
    val y: Float,
    val coordinateSpace: ProvisionalCoordinateSpace,
    val supportFrames: Int,
    val skyResidualPx: Float?,
    val cameraResidualPx: Float?,
    val confidence: Float,
    val notes: String
)

data class Stage6RegressionFixture(
    val name: String,
    val frames: List<ArgbPixelImage>,
    val referenceFrameIndex: Int,
    val groundTruth: List<ProvisionalSourceLabel>
) {
    val referenceStars: List<DetectedStar> = groundTruth
        .filter { it.classification == ProvisionalSourceClass.STAR }
        .map {
            DetectedStar(
                x = it.x,
                y = it.y,
                flux = 500f,
                localBackground = 20f,
                localContrast = 60f,
                width = 1.8f,
                ellipticity = 0.1f,
                confidence = it.confidence
            )
        }

    val scoredGroundTruth: List<ProvisionalSourceLabel> = groundTruth
        .filterNot { it.classification == ProvisionalSourceClass.UNCERTAIN }
}

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
        val groundTruthName = properties.getProperty("groundTruth")
        val groundTruth = if (groundTruthName != null) {
            readGroundTruth(directory.resolve(groundTruthName))
        } else {
            readLegacyReferenceStars(
                directory.resolve(properties.getProperty("referenceStars", "reference-stars.csv"))
            )
        }
        require(groundTruth.any { it.classification == ProvisionalSourceClass.STAR }) {
            "Stage 6 fixture needs at least one provisional star"
        }
        require(groundTruth.map { it.id }.distinct().size == groundTruth.size) {
            "Provisional ground-truth ids must be unique"
        }
        groundTruth.forEach { label ->
            require(label.x >= 0f && label.x < frames.first().width.toFloat()) {
                "Ground-truth x is outside the fixture: $label"
            }
            require(label.y >= 0f && label.y < frames.first().height.toFloat()) {
                "Ground-truth y is outside the fixture: $label"
            }
            require(label.supportFrames in 1..frames.size) {
                "Ground-truth support is outside the series: $label"
            }
            require(label.confidence in 0f..1f) {
                "Ground-truth confidence is outside 0..1: $label"
            }
        }
        return Stage6RegressionFixture(
            properties.getProperty("name", directory.name),
            frames,
            referenceIndex,
            groundTruth
        )
    }

    private fun readGroundTruth(file: File): List<ProvisionalSourceLabel> = file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .map { line ->
            val values = line.split(',', limit = 10).map(String::trim)
            require(values.size >= 9) { "Invalid provisional ground-truth row: $line" }
            val classification = enumValueOf<ProvisionalSourceClass>(values[1].uppercase())
            val coordinateSpace = enumValueOf<ProvisionalCoordinateSpace>(values[4].uppercase())
            require(
                classification != ProvisionalSourceClass.STAR ||
                    coordinateSpace == ProvisionalCoordinateSpace.SKY
            ) { "A star must use sky coordinates: $line" }
            require(
                classification != ProvisionalSourceClass.SENSOR_DEFECT ||
                    coordinateSpace == ProvisionalCoordinateSpace.CAMERA
            ) { "A sensor defect must use camera coordinates: $line" }
            ProvisionalSourceLabel(
                id = values[0],
                classification = classification,
                x = values[2].toFloat(),
                y = values[3].toFloat(),
                coordinateSpace = coordinateSpace,
                supportFrames = values[5].toInt(),
                skyResidualPx = values[6].toFloatOrNull(),
                cameraResidualPx = values[7].toFloatOrNull(),
                confidence = values[8].toFloat(),
                notes = values.getOrElse(9) { "" }
            )
        }
        .toList()

    private fun readLegacyReferenceStars(file: File): List<ProvisionalSourceLabel> =
        file.readLines()
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .mapIndexed { index, line ->
                val values = line.split(',').map(String::trim)
                require(values.size >= 2) { "Invalid reference star row: $line" }
                ProvisionalSourceLabel(
                    id = "legacy-star-${index + 1}",
                    classification = ProvisionalSourceClass.STAR,
                    x = values[0].toFloat(),
                    y = values[1].toFloat(),
                    coordinateSpace = ProvisionalCoordinateSpace.SKY,
                    supportFrames = 1,
                    skyResidualPx = null,
                    cameraResidualPx = null,
                    confidence = values.getOrNull(7)?.toFloatOrNull() ?: 0.95f,
                    notes = "Legacy reference-star row"
                )
            }
            .toList()
}
