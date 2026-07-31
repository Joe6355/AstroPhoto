package com.example.astrophoto

import com.example.astrophoto.processing.jpeg.v2.model.DetectedStar
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO

internal data class Stage6RegressionFixture(
    val name: String,
    val frames: List<ArgbPixelImage>,
    val referenceFrameIndex: Int,
    val groundTruth: List<ProvisionalSourceLabel>,
    val groundTruthMetadata: GroundTruthMetadata
) {
    val strictReferenceStarLabels: List<ProvisionalSourceLabel> = groundTruth
        .filter {
            GroundTruthEligibility.isEligible(it, StrictGroundTruthMetric.STAR_RETENTION)
        }

    val referenceStars: List<DetectedStar> = strictReferenceStarLabels
        .map {
            DetectedStar(
                x = it.x.toFloat(),
                y = it.y.toFloat(),
                flux = 500f,
                localBackground = 20f,
                localContrast = 60f,
                width = 1.8f,
                ellipticity = 0.1f,
                confidence = it.processingConfidence
            )
        }

    val provisionalReferenceStars: List<ProvisionalSourceLabel> = groundTruth
        .filter { it.classification == ProvisionalSourceClass.STAR }

    val strictSensorDefects: List<ProvisionalSourceLabel> = groundTruth
        .filter {
            GroundTruthEligibility.isEligible(it, StrictGroundTruthMetric.SENSOR_DEFECT)
        }

    val scoredGroundTruth: List<ProvisionalSourceLabel> = GroundTruthEligibility.eligible(groundTruth)

    val groundTruthSummary: GroundTruthEligibilitySummary = GroundTruthEligibility.summary(groundTruth)
}

internal object Stage6RegressionFixtureLoader {
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
        val metadata = GroundTruthMetadata.load(
            properties.getProperty("groundTruthMetadata")
                ?.let(directory::resolve)
        )
        val groundTruthName = properties.getProperty("groundTruth")
        val groundTruth = if (groundTruthName != null) {
            GroundTruthCsv.read(directory.resolve(groundTruthName), metadata.provenance)
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
            require(label.x >= 0.0 && label.x < frames.first().width.toDouble()) {
                "Ground-truth x is outside the fixture: $label"
            }
            require(label.y >= 0.0 && label.y < frames.first().height.toDouble()) {
                "Ground-truth y is outside the fixture: $label"
            }
            require(label.supportFrames in 1..frames.size) {
                "Ground-truth support is outside the series: $label"
            }
            require(label.confidence == null || label.confidence in 0.0..1.0) {
                "Ground-truth confidence is outside 0..1: $label"
            }
        }
        metadata.fixtureWidth?.let {
            require(it == frames.first().width) { "ground-truth metadata width differs from fixture" }
        }
        metadata.fixtureHeight?.let {
            require(it == frames.first().height) { "ground-truth metadata height differs from fixture" }
        }
        metadata.referenceFrameIndex?.let {
            require(it == referenceIndex) { "ground-truth metadata reference index differs from fixture" }
        }
        return Stage6RegressionFixture(
            properties.getProperty("name", directory.name),
            frames,
            referenceIndex,
            groundTruth,
            metadata
        )
    }

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
                    x = values[0].toDouble(),
                    y = values[1].toDouble(),
                    coordinateSpace = ProvisionalCoordinateSpace.SKY,
                    supportFrames = 1,
                    skyResidualPx = null,
                    cameraResidualPx = null,
                    confidence = values.getOrNull(7)?.toDoubleOrNull() ?: 0.95,
                    annotationSource = GroundTruthAnnotationSource.UNKNOWN,
                    reviewStatus = GroundTruthReviewStatus.NEEDS_REVIEW,
                    reviewedBy = "",
                    reviewedAt = "",
                    notes = "Legacy reference-star row"
                )
            }
            .toList()
}
