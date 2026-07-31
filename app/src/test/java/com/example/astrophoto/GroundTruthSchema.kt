package com.example.astrophoto

import java.io.File
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.Properties
import kotlin.math.roundToInt

internal const val GROUND_TRUTH_SCHEMA_VERSION = "astrophoto.ground-truth/2"

enum class ProvisionalSourceClass {
    STAR,
    SENSOR_DEFECT,
    UNCERTAIN,
    UNKNOWN
}

enum class ProvisionalCoordinateSpace {
    SKY,
    CAMERA,
    FIXTURE,
    REFERENCE,
    OUTPUT,
    UNKNOWN
}

enum class GroundTruthAnnotationSource {
    MANUAL,
    AUTOMATIC,
    CATALOG,
    DERIVED,
    UNKNOWN
}

enum class GroundTruthReviewStatus {
    CONFIRMED,
    UNREVIEWED,
    REJECTED,
    NEEDS_REVIEW,
    UNKNOWN
}

data class ProvisionalSourceLabel(
    val id: String,
    val classification: ProvisionalSourceClass,
    val x: Double,
    val y: Double,
    val coordinateSpace: ProvisionalCoordinateSpace,
    val supportFrames: Int,
    val skyResidualPx: Double?,
    val cameraResidualPx: Double?,
    val confidence: Double?,
    val annotationSource: GroundTruthAnnotationSource,
    val reviewStatus: GroundTruthReviewStatus,
    val reviewedBy: String,
    val reviewedAt: String,
    val notes: String
) {
    val processingConfidence: Float get() = (confidence ?: 0.0).toFloat()
}

internal data class LegacyGroundTruthProvenance(
    val confirmedManualIds: Set<String> = emptySet(),
    val automaticIdPrefixes: List<String> = listOf("candidate-")
) {
    fun sourceAndStatus(id: String): Pair<GroundTruthAnnotationSource, GroundTruthReviewStatus> =
        when {
            id in confirmedManualIds ->
                GroundTruthAnnotationSource.MANUAL to GroundTruthReviewStatus.CONFIRMED
            automaticIdPrefixes.any(id::startsWith) ->
                GroundTruthAnnotationSource.AUTOMATIC to GroundTruthReviewStatus.UNREVIEWED
            else ->
                GroundTruthAnnotationSource.UNKNOWN to GroundTruthReviewStatus.NEEDS_REVIEW
        }
}

internal data class GroundTruthMetadata(
    val schemaVersion: String,
    val fixtureWidth: Int?,
    val fixtureHeight: Int?,
    val referenceFrameIndex: Int?,
    val fixtureCoordinates: String,
    val referenceCoordinates: String,
    val cameraCoordinates: String,
    val outputCoordinates: String,
    val legacySha256: String?,
    val provenance: LegacyGroundTruthProvenance
) {
    companion object {
        fun load(file: File?): GroundTruthMetadata {
            if (file == null || !file.isFile) {
                return GroundTruthMetadata(
                    schemaVersion = "unknown",
                    fixtureWidth = null,
                    fixtureHeight = null,
                    referenceFrameIndex = null,
                    fixtureCoordinates = "unknown",
                    referenceCoordinates = "unknown",
                    cameraCoordinates = "unknown",
                    outputCoordinates = "unknown",
                    legacySha256 = null,
                    provenance = LegacyGroundTruthProvenance()
                )
            }
            val properties = Properties().apply { file.inputStream().use(::load) }
            fun list(name: String): List<String> = properties.getProperty(name)
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()
            return GroundTruthMetadata(
                schemaVersion = properties.getProperty("schemaVersion", "unknown"),
                fixtureWidth = properties.getProperty("fixtureWidth")?.toIntOrNull(),
                fixtureHeight = properties.getProperty("fixtureHeight")?.toIntOrNull(),
                referenceFrameIndex = properties.getProperty("referenceFrameIndex")?.toIntOrNull(),
                fixtureCoordinates = properties.getProperty("fixtureCoordinates", "unknown"),
                referenceCoordinates = properties.getProperty("referenceCoordinates", "unknown"),
                cameraCoordinates = properties.getProperty("cameraCoordinates", "unknown"),
                outputCoordinates = properties.getProperty("outputCoordinates", "unknown"),
                legacySha256 = properties.getProperty("legacyGroundTruthSha256")?.trim()?.ifEmpty { null },
                provenance = LegacyGroundTruthProvenance(
                    confirmedManualIds = list("confirmedManualIds").toSet(),
                    automaticIdPrefixes = list("automaticIdPrefixes").ifEmpty { listOf("candidate-") }
                )
            )
        }
    }
}

internal enum class StrictGroundTruthMetric {
    STAR_RETENTION,
    SENSOR_DEFECT
}

internal object GroundTruthEligibility {
    private val confirmedSources = setOf(
        GroundTruthAnnotationSource.MANUAL,
        GroundTruthAnnotationSource.CATALOG
    )

    fun isEligible(label: ProvisionalSourceLabel, metric: StrictGroundTruthMetric): Boolean {
        if (label.reviewStatus != GroundTruthReviewStatus.CONFIRMED) return false
        if (label.annotationSource !in confirmedSources) return false
        return when (metric) {
            StrictGroundTruthMetric.STAR_RETENTION ->
                label.classification == ProvisionalSourceClass.STAR
            StrictGroundTruthMetric.SENSOR_DEFECT ->
                label.classification == ProvisionalSourceClass.SENSOR_DEFECT
        }
    }

    fun eligible(labels: List<ProvisionalSourceLabel>): List<ProvisionalSourceLabel> =
        labels.filter { label -> StrictGroundTruthMetric.entries.any { isEligible(label, it) } }

    fun summary(labels: List<ProvisionalSourceLabel>): GroundTruthEligibilitySummary =
        GroundTruthEligibilitySummary(
            totalRows = labels.size,
            eligibleConfirmedRows = eligible(labels).size,
            eligibleConfirmedStars = labels.count {
                isEligible(it, StrictGroundTruthMetric.STAR_RETENTION)
            },
            eligibleConfirmedSensorDefects = labels.count {
                isEligible(it, StrictGroundTruthMetric.SENSOR_DEFECT)
            },
            excludedAutomaticRows = labels.count {
                it.annotationSource == GroundTruthAnnotationSource.AUTOMATIC
            },
            excludedUncertainRows = labels.count {
                it.classification == ProvisionalSourceClass.UNCERTAIN
            },
            excludedUnreviewedRows = labels.count {
                it.reviewStatus == GroundTruthReviewStatus.UNREVIEWED
            },
            excludedNeedsReviewRows = labels.count {
                it.reviewStatus == GroundTruthReviewStatus.NEEDS_REVIEW
            },
            excludedRejectedRows = labels.count {
                it.reviewStatus == GroundTruthReviewStatus.REJECTED
            },
            excludedUnknownRows = labels.count {
                it.classification == ProvisionalSourceClass.UNKNOWN ||
                    it.annotationSource == GroundTruthAnnotationSource.UNKNOWN ||
                    it.reviewStatus == GroundTruthReviewStatus.UNKNOWN
            }
        )
}

internal data class GroundTruthEligibilitySummary(
    val totalRows: Int,
    val eligibleConfirmedRows: Int,
    val eligibleConfirmedStars: Int,
    val eligibleConfirmedSensorDefects: Int,
    val excludedAutomaticRows: Int,
    val excludedUncertainRows: Int,
    val excludedUnreviewedRows: Int,
    val excludedNeedsReviewRows: Int,
    val excludedRejectedRows: Int,
    val excludedUnknownRows: Int
)

internal object GroundTruthStableIds {
    fun candidate(x: Double, y: Double): String =
        "candidate-x${(x * 100).roundToInt().toString().padStart(5, '0')}" +
            "-y${(y * 100).roundToInt().toString().padStart(5, '0')}"
}

internal object GroundTruthCsv {
    val header: List<String> = listOf(
        "id",
        "x",
        "y",
        "class",
        "confidence",
        "annotation_source",
        "review_status",
        "reviewed_by",
        "reviewed_at",
        "notes",
        "coordinate_space",
        "support_frames",
        "sky_residual_px",
        "camera_residual_px"
    )

    fun read(file: File, provenance: LegacyGroundTruthProvenance = LegacyGroundTruthProvenance()):
        List<ProvisionalSourceLabel> = read(file.readText(StandardCharsets.UTF_8), provenance)

    fun read(text: String, provenance: LegacyGroundTruthProvenance = LegacyGroundTruthProvenance()):
        List<ProvisionalSourceLabel> {
        val records = CsvCodec.parse(text)
            .filterNot { record ->
                record.all(String::isBlank) ||
                    record.firstOrNull()?.trimStart('\uFEFF')?.trim()?.startsWith('#') == true
            }
        if (records.isEmpty()) return emptyList()
        val first = records.first().map { it.trimStart('\uFEFF').trim().lowercase(Locale.US) }
        val hasHeader = "id" in first && "class" in first
        val labels = if (hasHeader) {
            val columns = first.withIndex().associate { it.value to it.index }
            records.drop(1).map { readMappedRow(it, columns, provenance) }
        } else {
            records.map { readLegacyRow(it, provenance) }
        }
        require(labels.map { it.id }.distinct().size == labels.size) {
            "Ground-truth ids must be unique"
        }
        return labels
    }

    fun encode(labels: List<ProvisionalSourceLabel>): String = buildString {
        append("# schemaVersion=")
        append(GROUND_TRUTH_SCHEMA_VERSION)
        append('\n')
        append(CsvCodec.encodeRow(header))
        append('\n')
        labels.forEach { label ->
            append(
                CsvCodec.encodeRow(
                    listOf(
                        label.id,
                        decimal(label.x),
                        decimal(label.y),
                        label.classification.name.lowercase(Locale.US),
                        label.confidence?.let(::decimal).orEmpty(),
                        label.annotationSource.name.lowercase(Locale.US),
                        label.reviewStatus.name.lowercase(Locale.US),
                        label.reviewedBy,
                        label.reviewedAt,
                        label.notes,
                        label.coordinateSpace.name.lowercase(Locale.US),
                        label.supportFrames.toString(),
                        label.skyResidualPx?.let(::decimal).orEmpty(),
                        label.cameraResidualPx?.let(::decimal).orEmpty()
                    )
                )
            )
            append('\n')
        }
    }

    fun write(file: Path, labels: List<ProvisionalSourceLabel>) {
        Files.createDirectories(checkNotNull(file.parent))
        Files.writeString(file, encode(labels), StandardCharsets.UTF_8)
    }

    fun writeAtomically(file: Path, labels: List<ProvisionalSourceLabel>) {
        val parent = checkNotNull(file.toAbsolutePath().parent)
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".ground-truth-", ".tmp")
        try {
            Files.writeString(temporary, encode(labels), StandardCharsets.UTF_8)
            Files.move(
                temporary,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readMappedRow(
        values: List<String>,
        columns: Map<String, Int>,
        provenance: LegacyGroundTruthProvenance
    ): ProvisionalSourceLabel {
        fun value(name: String): String = columns[name]?.let { values.getOrElse(it) { "" } }?.trim().orEmpty()
        val id = value("id")
        val hasExplicitProvenance = "annotation_source" in columns || "review_status" in columns
        val migrated = provenance.sourceAndStatus(id)
        return validate(
            ProvisionalSourceLabel(
                id = id,
                classification = enumOrUnknown(value("class"), ProvisionalSourceClass.UNKNOWN),
                x = value("x").toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid ground-truth x for $id"),
                y = value("y").toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid ground-truth y for $id"),
                coordinateSpace = enumOrUnknown(
                    value("coordinate_space"),
                    ProvisionalCoordinateSpace.UNKNOWN
                ),
                supportFrames = value("support_frames").toIntOrNull() ?: 1,
                skyResidualPx = value("sky_residual_px").toDoubleOrNull(),
                cameraResidualPx = value("camera_residual_px").toDoubleOrNull(),
                confidence = value("confidence").toDoubleOrNull(),
                annotationSource = if (hasExplicitProvenance) {
                    enumOrUnknown(value("annotation_source"), GroundTruthAnnotationSource.UNKNOWN)
                } else migrated.first,
                reviewStatus = if (hasExplicitProvenance) {
                    enumOrUnknown(value("review_status"), GroundTruthReviewStatus.UNKNOWN)
                } else migrated.second,
                reviewedBy = value("reviewed_by"),
                reviewedAt = value("reviewed_at"),
                notes = value("notes")
            )
        )
    }

    private fun readLegacyRow(
        values: List<String>,
        provenance: LegacyGroundTruthProvenance
    ): ProvisionalSourceLabel {
        require(values.size >= 9) { "Invalid legacy provisional ground-truth row: $values" }
        val id = values[0].trim()
        val sourceAndStatus = provenance.sourceAndStatus(id)
        return validate(
            ProvisionalSourceLabel(
                id = id,
                classification = enumOrUnknown(values[1], ProvisionalSourceClass.UNKNOWN),
                x = values[2].trim().toDouble(),
                y = values[3].trim().toDouble(),
                coordinateSpace = enumOrUnknown(values[4], ProvisionalCoordinateSpace.UNKNOWN),
                supportFrames = values[5].trim().toInt(),
                skyResidualPx = values[6].trim().toDoubleOrNull(),
                cameraResidualPx = values[7].trim().toDoubleOrNull(),
                confidence = values[8].trim().toDoubleOrNull(),
                annotationSource = sourceAndStatus.first,
                reviewStatus = sourceAndStatus.second,
                reviewedBy = "",
                reviewedAt = "",
                notes = values.drop(9).joinToString(",").trim()
            )
        )
    }

    private fun validate(label: ProvisionalSourceLabel): ProvisionalSourceLabel {
        require(label.id.isNotBlank()) { "Ground-truth id must not be blank" }
        require(label.x.isFinite() && label.y.isFinite()) { "Ground-truth coordinates must be finite: ${label.id}" }
        require(label.supportFrames >= 1) { "Ground-truth support must be positive: ${label.id}" }
        require(label.confidence == null || label.confidence in 0.0..1.0) {
            "Ground-truth confidence is outside 0..1: ${label.id}"
        }
        require(isIso8601OrBlank(label.reviewedAt)) {
            "reviewed_at must be ISO-8601 or empty: ${label.id}"
        }
        require(
            label.classification != ProvisionalSourceClass.STAR ||
                label.coordinateSpace in setOf(
                    ProvisionalCoordinateSpace.SKY,
                    ProvisionalCoordinateSpace.REFERENCE,
                    ProvisionalCoordinateSpace.FIXTURE,
                    ProvisionalCoordinateSpace.UNKNOWN
                )
        ) { "A star must use sky/reference/fixture coordinates: ${label.id}" }
        require(
            label.classification != ProvisionalSourceClass.SENSOR_DEFECT ||
                label.coordinateSpace in setOf(
                    ProvisionalCoordinateSpace.CAMERA,
                    ProvisionalCoordinateSpace.UNKNOWN
                )
        ) { "A sensor defect must use camera coordinates: ${label.id}" }
        return label
    }

    private inline fun <reified T : Enum<T>> enumOrUnknown(value: String, unknown: T): T =
        enumValues<T>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: unknown

    private fun decimal(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun isIso8601OrBlank(value: String): Boolean {
        if (value.isBlank()) return true
        return try {
            Instant.parse(value)
            true
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(value)
                true
            } catch (_: DateTimeParseException) {
                false
            }
        }
    }
}

internal object CsvCodec {
    fun parse(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                quoted && character == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                character == '"' -> quoted = !quoted
                !quoted && character == ',' -> {
                    record += field.toString()
                    field.setLength(0)
                }
                !quoted && (character == '\n' || character == '\r') -> {
                    record += field.toString()
                    field.setLength(0)
                    records += record
                    record = mutableListOf()
                    if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                }
                else -> field.append(character)
            }
            index++
        }
        require(!quoted) { "Unterminated quoted CSV field" }
        if (field.isNotEmpty() || record.isNotEmpty()) {
            record += field.toString()
            records += record
        }
        return records
    }

    fun encodeRow(values: List<String>): String = values.joinToString(",") { value ->
        if (
            value.contains(',') || value.contains('"') || value.contains('\n') ||
            value.contains('\r') || value != value.trim()
        ) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}

internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
