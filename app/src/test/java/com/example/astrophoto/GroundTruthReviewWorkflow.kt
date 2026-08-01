package com.example.astrophoto

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.math.hypot
import kotlin.math.roundToInt

internal data class GroundTruthReviewPackageResult(
    val manifest: Path,
    val queue: Path,
    val manifestSha256: String,
    val queueSha256: String,
    val outputFiles: List<Path>
)

internal class GroundTruthReviewPackageGenerator {
    fun generate(
        fixture: Stage6RegressionFixture,
        bundle: Stage6DiagnosticBundle,
        groundTruthFile: Path,
        outputRoot: Path
    ): GroundTruthReviewPackageResult {
        requireBuildReportsPath(outputRoot)
        require(bundle.fixtureName == fixture.name) { "Diagnostic bundle belongs to another fixture" }
        require(bundle.width == fixture.frames.first().width && bundle.height == fixture.frames.first().height)
        recreateDirectory(outputRoot)

        val labelsById = fixture.groundTruth.associateBy { it.id }
        val candidates = bundle.candidates.sortedWith(
            compareBy<Stage6CandidateDiagnostic> { it.referenceY }
                .thenBy { it.referenceX }
                .thenBy { it.id }
        )
        require(candidates.map { it.id }.distinct().size == candidates.size)
        val reviewLabels = candidates.map { candidate ->
            labelsById[candidate.id] ?: candidate.asAutomaticLabel()
        }

        val queue = outputRoot.resolve("review-queue.csv")
        Files.writeString(queue, reviewQueue(candidates, reviewLabels), StandardCharsets.UTF_8)

        val diagnostics = Stage6CandidateDiagnosticRunner()
        diagnostics.writeCandidateContactSheet(
            bundle,
            outputRoot.resolve("candidates-contact-sheet.png")
        )
        diagnostics.writeTrailContactSheet(
            bundle,
            outputRoot.resolve("alignment-before-after.png")
        )
        writeCandidateCrops(bundle, candidates, outputRoot)
        writeReferenceOverlay(bundle.referenceImage, candidates, outputRoot.resolve("reference-overlay.png"))
        writeRecurrenceOverlay(
            bundle.referenceImage,
            candidates,
            cameraSpace = true,
            output = outputRoot.resolve("camera-space-recurrence.png")
        )
        writeRecurrenceOverlay(
            bundle.cleanStack,
            candidates,
            cameraSpace = false,
            output = outputRoot.resolve("sky-space-aligned.png")
        )
        writeSensorMaskOverlay(bundle, outputRoot.resolve("sensor-mask-overlay.png"))
        Files.writeString(
            outputRoot.resolve("index.html"),
            reviewHtml(fixture, candidates, reviewLabels),
            StandardCharsets.UTF_8
        )
        GroundTruthReviewEvidenceGenerator().generate(
            fixture = fixture,
            bundle = bundle,
            candidates = candidates,
            labels = reviewLabels,
            outputRoot = outputRoot.resolve("human-review")
        )

        val outputFiles = listFiles(outputRoot)
        val manifest = outputRoot.resolve("review-manifest.json")
        Files.writeString(
            manifest,
            reviewManifest(fixture, groundTruthFile, outputRoot, outputFiles),
            StandardCharsets.UTF_8
        )
        return GroundTruthReviewPackageResult(
            manifest = manifest,
            queue = queue,
            manifestSha256 = sha256(manifest),
            queueSha256 = sha256(queue),
            outputFiles = listFiles(outputRoot)
        )
    }

    private fun reviewQueue(
        candidates: List<Stage6CandidateDiagnostic>,
        labels: List<ProvisionalSourceLabel>
    ): String = buildString {
        append("# Fill final_class, review_status, reviewer, reviewed_at and review_notes explicitly.\n")
        append(
            CsvCodec.encodeRow(
                listOf(
                    "id",
                    "x",
                    "y",
                    "coordinate_space",
                    "proposal_class",
                    "proposal_confidence",
                    "annotation_source",
                    "current_review_status",
                    "final_class",
                    "review_status",
                    "reviewer",
                    "reviewed_at",
                    "review_notes"
                )
            )
        )
        append('\n')
        candidates.zip(labels).forEach { (candidate, label) ->
            append(
                CsvCodec.encodeRow(
                    listOf(
                        candidate.id,
                        decimal(label.x),
                        decimal(label.y),
                        label.coordinateSpace.name.lowercase(Locale.US),
                        candidate.provisionalClass.name.lowercase(Locale.US),
                        label.confidence?.let(::decimal).orEmpty(),
                        label.annotationSource.name.lowercase(Locale.US),
                        label.reviewStatus.name.lowercase(Locale.US),
                        "",
                        "",
                        "",
                        "",
                        ""
                    )
                )
            )
            append('\n')
        }
    }

    private fun reviewManifest(
        fixture: Stage6RegressionFixture,
        groundTruthFile: Path,
        outputRoot: Path,
        files: List<Path>
    ): String {
        val summary = fixture.groundTruthSummary
        return buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": \"astrophoto.ground-truth-review/1\",")
            appendLine("  \"fixture\": ${json(fixture.name)},")
            appendLine("  \"groundTruthSchemaVersion\": ${json(fixture.groundTruthMetadata.schemaVersion)},")
            appendLine("  \"groundTruthSha256\": ${json(sha256(groundTruthFile))},")
            appendLine("  \"frameCount\": ${fixture.frames.size},")
            appendLine("  \"referenceFrameIndex\": ${fixture.referenceFrameIndex},")
            appendLine("  \"coordinateSpaces\": {")
            appendLine("    \"fixture\": ${json(fixture.groundTruthMetadata.fixtureCoordinates)},")
            appendLine("    \"reference\": ${json(fixture.groundTruthMetadata.referenceCoordinates)},")
            appendLine("    \"camera\": ${json(fixture.groundTruthMetadata.cameraCoordinates)},")
            appendLine("    \"output\": ${json(fixture.groundTruthMetadata.outputCoordinates)}")
            appendLine("  },")
            appendLine("  \"eligibility\": {")
            appendLine("    \"totalRows\": ${summary.totalRows},")
            appendLine("    \"eligibleConfirmedRows\": ${summary.eligibleConfirmedRows},")
            appendLine("    \"eligibleConfirmedStars\": ${summary.eligibleConfirmedStars},")
            appendLine("    \"eligibleConfirmedSensorDefects\": ${summary.eligibleConfirmedSensorDefects},")
            appendLine("    \"excludedAutomaticRows\": ${summary.excludedAutomaticRows},")
            appendLine("    \"excludedUncertainRows\": ${summary.excludedUncertainRows},")
            appendLine("    \"excludedUnreviewedRows\": ${summary.excludedUnreviewedRows},")
            appendLine("    \"excludedNeedsReviewRows\": ${summary.excludedNeedsReviewRows},")
            appendLine("    \"excludedRejectedRows\": ${summary.excludedRejectedRows},")
            appendLine("    \"excludedUnknownRows\": ${summary.excludedUnknownRows}")
            appendLine("  },")
            appendLine("  \"files\": [")
            files.forEachIndexed { index, file ->
                val relative = outputRoot.relativize(file).invariantSeparatorsPathString
                append("    {\"path\": ${json(relative)}, \"bytes\": ${Files.size(file)}, ")
                append("\"sha256\": ${json(sha256(file))}}")
                appendLine(if (index == files.lastIndex) "" else ",")
            }
            appendLine("  ]")
            appendLine("}")
        }
    }

    private fun reviewHtml(
        fixture: Stage6RegressionFixture,
        candidates: List<Stage6CandidateDiagnostic>,
        labels: List<ProvisionalSourceLabel>
    ): String = buildString {
        appendLine("<!doctype html>")
        appendLine("<html lang=\"en\"><head><meta charset=\"utf-8\">")
        appendLine("<title>Ground truth review: ${html(fixture.name)}</title>")
        appendLine(
            "<style>body{font-family:sans-serif;background:#111;color:#eee;margin:24px}" +
                "a{color:#7dd3fc}table{border-collapse:collapse;width:100%}" +
                "th,td{border:1px solid #555;padding:6px;vertical-align:top}" +
                "img.crop{width:164px;height:164px;image-rendering:pixelated}</style></head><body>"
        )
        appendLine("<h1>Ground truth review: ${html(fixture.name)}</h1>")
        appendLine("<p>Automatic candidates are proposals and are excluded from strict metrics until explicitly reviewed and imported.</p>")
        appendLine("<p><a href=\"review-queue.csv\">review queue</a> | <a href=\"review-manifest.json\">manifest</a></p>")
        appendLine("<p><img src=\"candidates-contact-sheet.png\" alt=\"all candidates\"></p>")
        appendLine("<p><a href=\"reference-overlay.png\">reference overlay</a> | <a href=\"camera-space-recurrence.png\">camera recurrence</a> | <a href=\"sky-space-aligned.png\">sky aligned</a> | <a href=\"alignment-before-after.png\">before/after alignment</a> | <a href=\"sensor-mask-overlay.png\">sensor mask</a></p>")
        appendLine("<table><thead><tr><th>candidate</th><th>evidence</th><th>review state</th><th>crops</th></tr></thead><tbody>")
        candidates.zip(labels).forEach { (candidate, label) ->
            val nearestStar = nearest(candidate, fixture.strictReferenceStarLabels)
            val nearestDefect = nearest(candidate, fixture.strictSensorDefects)
            val eligible = StrictGroundTruthMetric.entries.any {
                GroundTruthEligibility.isEligible(label, it)
            }
            val safeId = safeName(candidate.id)
            appendLine("<tr><td><b>${html(candidate.id)}</b><br>${html(candidate.provisionalClass.name.lowercase())} (${decimal(candidate.confidence)})<br>x=${decimal(candidate.referenceX)}, y=${decimal(candidate.referenceY)}<br>${html(label.coordinateSpace.name.lowercase())}<br>footprint=${candidate.boundingBox.width}x${candidate.boundingBox.height}</td>")
            appendLine("<td>camera=${candidate.cameraSpaceRecurrence}/${fixture.frames.size}: ${candidate.cameraObservedFrameIndices.joinToString()}<br>sky=${candidate.skySpaceRecurrence}/${fixture.frames.size}: ${candidate.skyObservedFrameIndices.joinToString()}<br>origins=${html(candidate.origins.joinToString())}<br>nearest confirmed star=${html(nearestStar)}<br>nearest confirmed defect=${html(nearestDefect)}<br>${html(candidate.classificationReason)}</td>")
            appendLine("<td>source=${html(label.annotationSource.name.lowercase())}<br>status=${html(label.reviewStatus.name.lowercase())}<br>strict eligible=$eligible</td>")
            val maskLink = if (candidate.provisionalClass == ProvisionalSourceClass.SENSOR_DEFECT) {
                " | <a href=\"crops/mask/$safeId.png\">mask</a>"
            } else {
                ""
            }
            appendLine("<td><a href=\"crops/native/$safeId.png\">native</a> | <a href=\"crops/grid/$safeId.png\">grid</a>$maskLink<br><img class=\"crop\" src=\"crops/nearest/$safeId.png\" alt=\"${html(candidate.id)}\"><br><a href=\"crops/alignment-before/$safeId.png\">before alignment/filtering</a> | <a href=\"crops/alignment-after/$safeId.png\">after alignment/filtering</a></td></tr>")
        }
        appendLine("</tbody></table></body></html>")
    }

    private fun writeCandidateCrops(
        bundle: Stage6DiagnosticBundle,
        candidates: List<Stage6CandidateDiagnostic>,
        outputRoot: Path
    ) {
        val source = buffered(bundle.referenceImage)
        val alignmentBefore = buffered(bundle.leakyManualAlignedStack)
        val alignmentAfter = buffered(bundle.manualAlignedStack)
        try {
            candidates.forEach { candidate ->
                val native = crop(source, candidate.referenceX, candidate.referenceY, CROP_SIZE)
                val enlarged = scaleNearest(native, CROP_SCALE)
                val beforeNative = crop(
                    alignmentBefore,
                    candidate.referenceX,
                    candidate.referenceY,
                    CROP_SIZE
                )
                val afterNative = crop(
                    alignmentAfter,
                    candidate.referenceX,
                    candidate.referenceY,
                    CROP_SIZE
                )
                val before = scaleNearest(beforeNative, CROP_SCALE)
                val after = scaleNearest(afterNative, CROP_SCALE)
                val gridded = copy(enlarged)
                val graphics = gridded.createGraphics()
                try {
                    graphics.color = Color(255, 255, 255, 80)
                    for (position in 0 until gridded.width step CROP_SCALE) {
                        graphics.drawLine(position, 0, position, gridded.height - 1)
                        graphics.drawLine(0, position, gridded.width - 1, position)
                    }
                    graphics.color = Color.YELLOW
                    graphics.stroke = BasicStroke(2f)
                    val center = gridded.width / 2
                    graphics.drawLine(center - 10, center, center + 10, center)
                    graphics.drawLine(center, center - 10, center, center + 10)
                    graphics.font = Font(Font.MONOSPACED, Font.BOLD, 10)
                    graphics.drawString(candidate.id, 3, 12)
                } finally {
                    graphics.dispose()
                }
                val name = safeName(candidate.id) + ".png"
                writePng(native, outputRoot.resolve("crops/native/$name"))
                writePng(enlarged, outputRoot.resolve("crops/nearest/$name"))
                writePng(gridded, outputRoot.resolve("crops/grid/$name"))
                writePng(before, outputRoot.resolve("crops/alignment-before/$name"))
                writePng(after, outputRoot.resolve("crops/alignment-after/$name"))
                native.flush()
                enlarged.flush()
                gridded.flush()
                beforeNative.flush()
                afterNative.flush()
                before.flush()
                after.flush()
            }
        } finally {
            source.flush()
            alignmentBefore.flush()
            alignmentAfter.flush()
        }
    }

    private fun writeReferenceOverlay(
        source: ArgbPixelImage,
        candidates: List<Stage6CandidateDiagnostic>,
        output: Path
    ) {
        val image = buffered(source)
        val graphics = image.createGraphics()
        try {
            configure(graphics)
            graphics.font = Font(Font.MONOSPACED, Font.BOLD, 10)
            candidates.forEach { candidate ->
                graphics.color = classColor(candidate.provisionalClass)
                graphics.stroke = BasicStroke(2f)
                val x = candidate.referenceX.roundToInt()
                val y = candidate.referenceY.roundToInt()
                graphics.drawOval(x - 7, y - 7, 14, 14)
                graphics.drawString(candidate.id, x + 9, y - 5)
            }
        } finally {
            graphics.dispose()
        }
        writePng(image, output)
        image.flush()
    }

    private fun writeRecurrenceOverlay(
        source: ArgbPixelImage,
        candidates: List<Stage6CandidateDiagnostic>,
        cameraSpace: Boolean,
        output: Path
    ) {
        val image = buffered(source)
        val graphics = image.createGraphics()
        try {
            configure(graphics)
            graphics.font = Font(Font.MONOSPACED, Font.BOLD, 10)
            candidates.forEach { candidate ->
                val recurrence = if (cameraSpace) {
                    candidate.cameraSpaceRecurrence
                } else {
                    candidate.skySpaceRecurrence
                }
                val intensity = (60 + recurrence * 195 / 30).coerceIn(60, 255)
                graphics.color = if (cameraSpace) {
                    Color(intensity, 70, 70)
                } else {
                    Color(70, intensity, 255)
                }
                val x = candidate.referenceX.roundToInt()
                val y = candidate.referenceY.roundToInt()
                val radius = 4 + recurrence / 5
                graphics.drawOval(x - radius, y - radius, radius * 2, radius * 2)
                graphics.drawString("${candidate.id}:$recurrence", x + radius + 2, y)
            }
        } finally {
            graphics.dispose()
        }
        writePng(image, output)
        image.flush()
    }

    private fun writeSensorMaskOverlay(bundle: Stage6DiagnosticBundle, output: Path) {
        val image = buffered(bundle.referenceImage)
        val mask = bundle.sensorDefectMask
        if (mask != null) {
            mask.footprintPixels.forEach { pixel ->
                val original = image.getRGB(pixel.x, pixel.y)
                val green = (original ushr 8 and 0xFF) / 3
                val blue = (original and 0xFF) / 3
                image.setRGB(pixel.x, pixel.y, 0xFF000000.toInt() or (255 shl 16) or (green shl 8) or blue)
            }
            val graphics = image.createGraphics()
            try {
                configure(graphics)
                graphics.color = Color.RED
                graphics.stroke = BasicStroke(2f)
                graphics.font = Font(Font.MONOSPACED, Font.BOLD, 10)
                mask.regions.sortedBy { it.stableRegionId }.forEach { region ->
                    val x = region.sourceX.roundToInt()
                    val y = region.sourceY.roundToInt()
                    graphics.drawOval(x - 7, y - 7, 14, 14)
                    graphics.drawString(region.stableRegionId, x + 9, y)
                }
            } finally {
                graphics.dispose()
            }
        }
        writePng(image, output)
        bundle.candidates
            .filter { it.provisionalClass == ProvisionalSourceClass.SENSOR_DEFECT }
            .forEach { candidate ->
                val native = crop(image, candidate.referenceX, candidate.referenceY, CROP_SIZE)
                val enlarged = scaleNearest(native, CROP_SCALE)
                writePng(
                    enlarged,
                    checkNotNull(output.parent).resolve("crops/mask/${safeName(candidate.id)}.png")
                )
                native.flush()
                enlarged.flush()
            }
        image.flush()
    }

    private fun nearest(
        candidate: Stage6CandidateDiagnostic,
        labels: List<ProvisionalSourceLabel>
    ): String {
        val nearest = labels.minByOrNull { hypot(candidate.referenceX - it.x, candidate.referenceY - it.y) }
            ?: return "none"
        return "${nearest.id} (${decimal(hypot(candidate.referenceX - nearest.x, candidate.referenceY - nearest.y))} px)"
    }

    private fun recreateDirectory(output: Path) {
        if (Files.exists(output)) {
            Files.walk(output).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
        Files.createDirectories(output)
    }

    private fun listFiles(root: Path): List<Path> = Files.walk(root).use { stream ->
        stream.filter(Files::isRegularFile)
            .filter { it.fileName.toString() != "review-manifest.json" }
            .sorted(compareBy { root.relativize(it).invariantSeparatorsPathString })
            .toList()
    }

    private fun requireBuildReportsPath(output: Path) {
        val normalized = output.toAbsolutePath().normalize().invariantSeparatorsPathString.lowercase(Locale.US)
        require(normalized.contains("/app/build/reports/")) {
            "Review packages must be generated under app/build/reports: $output"
        }
    }

    private fun crop(source: BufferedImage, centerX: Double, centerY: Double, size: Int): BufferedImage {
        val result = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val left = centerX.roundToInt() - size / 2
        val top = centerY.roundToInt() - size / 2
        for (y in 0 until size) for (x in 0 until size) {
            val sourceX = left + x
            val sourceY = top + y
            if (sourceX in 0 until source.width && sourceY in 0 until source.height) {
                result.setRGB(x, y, source.getRGB(sourceX, sourceY))
            }
        }
        return result
    }

    private fun scaleNearest(source: BufferedImage, scale: Int): BufferedImage {
        val result = BufferedImage(source.width * scale, source.height * scale, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            )
            graphics.drawImage(source, 0, 0, result.width, result.height, null)
        } finally {
            graphics.dispose()
        }
        return result
    }

    private fun buffered(source: ArgbPixelImage): BufferedImage =
        BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB).also {
            it.setRGB(0, 0, source.width, source.height, source.pixels, 0, source.width)
        }

    private fun copy(source: BufferedImage): BufferedImage =
        BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB).also {
            val graphics = it.createGraphics()
            try {
                graphics.drawImage(source, 0, 0, null)
            } finally {
                graphics.dispose()
            }
        }

    private fun configure(graphics: java.awt.Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    private fun writePng(image: BufferedImage, output: Path) {
        Files.createDirectories(checkNotNull(output.parent))
        check(ImageIO.write(image, "png", output.toFile()))
    }

    private fun classColor(value: ProvisionalSourceClass): Color = when (value) {
        ProvisionalSourceClass.STAR -> Color.CYAN
        ProvisionalSourceClass.SENSOR_DEFECT -> Color.RED
        ProvisionalSourceClass.UNCERTAIN -> Color.YELLOW
        ProvisionalSourceClass.UNKNOWN -> Color.LIGHT_GRAY
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun decimal(value: Double): String =
        java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun json(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "\""

    private fun html(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        private const val CROP_SIZE = 41
        private const val CROP_SCALE = 4
    }
}

internal fun Stage6CandidateDiagnostic.asAutomaticLabel(): ProvisionalSourceLabel =
    ProvisionalSourceLabel(
        id = id,
        classification = provisionalClass,
        x = referenceX,
        y = referenceY,
        coordinateSpace = when (provisionalClass) {
            ProvisionalSourceClass.STAR -> ProvisionalCoordinateSpace.SKY
            ProvisionalSourceClass.SENSOR_DEFECT -> ProvisionalCoordinateSpace.CAMERA
            ProvisionalSourceClass.UNCERTAIN,
            ProvisionalSourceClass.UNKNOWN -> ProvisionalCoordinateSpace.UNKNOWN
        },
        supportFrames = when (provisionalClass) {
            ProvisionalSourceClass.STAR -> skySpaceRecurrence
            ProvisionalSourceClass.SENSOR_DEFECT -> cameraSpaceRecurrence
            ProvisionalSourceClass.UNCERTAIN,
            ProvisionalSourceClass.UNKNOWN -> maxOf(cameraSpaceRecurrence, skySpaceRecurrence)
        }.coerceAtLeast(1),
        skyResidualPx = skyResidual,
        cameraResidualPx = cameraResidual,
        confidence = confidence,
        annotationSource = GroundTruthAnnotationSource.AUTOMATIC,
        reviewStatus = GroundTruthReviewStatus.UNREVIEWED,
        reviewedBy = "",
        reviewedAt = "",
        notes = classificationReason
    )

internal data class GroundTruthImportResult(
    val output: Path,
    val auditLog: Path,
    val importedDecisionCount: Int,
    val outputSha256: String
)

internal class GroundTruthReviewImporter {
    fun importReview(
        inputGroundTruth: Path,
        reviewQueue: Path,
        outputGroundTruth: Path,
        auditLog: Path = outputGroundTruth.resolveSibling(outputGroundTruth.fileName.toString() + ".audit.json")
    ): GroundTruthImportResult {
        val input = inputGroundTruth.toAbsolutePath().normalize()
        val output = outputGroundTruth.toAbsolutePath().normalize()
        require(input != output) { "Review import must write a new ground-truth file" }
        require(auditLog.toAbsolutePath().normalize() != input) { "Audit log must not overwrite input" }
        require(auditLog.toAbsolutePath().normalize() != output) { "Audit log must differ from output" }

        val labels = GroundTruthCsv.read(input.toFile())
        val labelsById = labels.associateBy { it.id }
        val decisions = parseReviewQueue(reviewQueue)
        require(decisions.map { it.id }.distinct().size == decisions.size) {
            "Review queue contains duplicate IDs"
        }
        decisions.forEach { require(it.id in labelsById) { "Unknown review ID: ${it.id}" } }

        val explicit = decisions.filter(ReviewDecision::isExplicit)
        val replacements = linkedMapOf<String, ProvisionalSourceLabel>()
        explicit.forEach { decision ->
            val current = checkNotNull(labelsById[decision.id])
            val finalClass = parseReviewClass(decision.finalClass)
            val finalStatus = parseReviewStatus(decision.reviewStatus)
            require(decision.reviewer.isNotBlank()) { "Reviewer is required for ${decision.id}" }
            require(isIso8601(decision.reviewedAt)) { "reviewed_at must be ISO-8601 for ${decision.id}" }
            if (isAlreadyApplied(current, decision, finalClass, finalStatus)) return@forEach
            validateReadOnlyFields(current, decision)
            val manualStarAdjustment = isManualStarNeedsReviewAdjustment(
                current,
                finalClass,
                finalStatus
            )
            if (
                current.reviewStatus == GroundTruthReviewStatus.CONFIRMED &&
                current.annotationSource in setOf(
                    GroundTruthAnnotationSource.MANUAL,
                    GroundTruthAnnotationSource.CATALOG
                )
            ) {
                require(
                    manualStarAdjustment ||
                        finalClass == current.classification &&
                        finalStatus == GroundTruthReviewStatus.CONFIRMED
                ) {
                    "Conflicting decision for confirmed ground truth: ${decision.id}"
                }
            }
            replacements[decision.id] = current.copy(
                classification = finalClass,
                coordinateSpace = if (finalClass == current.classification || manualStarAdjustment) {
                    current.coordinateSpace
                } else {
                    when (finalClass) {
                        ProvisionalSourceClass.STAR -> ProvisionalCoordinateSpace.SKY
                        ProvisionalSourceClass.SENSOR_DEFECT -> ProvisionalCoordinateSpace.CAMERA
                        ProvisionalSourceClass.UNCERTAIN -> ProvisionalCoordinateSpace.UNKNOWN
                        ProvisionalSourceClass.UNKNOWN -> error("Unknown final class was rejected")
                    }
                },
                annotationSource = if (
                    current.annotationSource == GroundTruthAnnotationSource.CATALOG &&
                    finalClass == current.classification
                ) {
                    GroundTruthAnnotationSource.CATALOG
                } else {
                    GroundTruthAnnotationSource.MANUAL
                },
                reviewStatus = finalStatus,
                reviewedBy = decision.reviewer,
                reviewedAt = decision.reviewedAt,
                notes = resolvedReviewNotes(current.notes, decision.reviewNotes, manualStarAdjustment)
            )
        }
        val updated = labels.map { replacements[it.id] ?: it }
        val encoded = GroundTruthCsv.encode(updated)
        val outputHash = sha256(encoded.toByteArray(StandardCharsets.UTF_8))
        val beforeHash = sha256(input)
        val audit = auditJson(beforeHash, outputHash, labelsById, replacements)

        GroundTruthCsv.writeAtomically(output, updated)
        writeStringAtomically(auditLog.toAbsolutePath().normalize(), audit)
        require(sha256(output) == outputHash) { "Imported ground-truth hash differs after write" }
        return GroundTruthImportResult(output, auditLog, replacements.size, outputHash)
    }

    private fun parseReviewQueue(path: Path): List<ReviewDecision> {
        val records = CsvCodec.parse(Files.readString(path, StandardCharsets.UTF_8))
            .filterNot { it.all(String::isBlank) || it.firstOrNull()?.trim()?.startsWith('#') == true }
        require(records.isNotEmpty()) { "Review queue is empty" }
        val header = records.first().map { it.trim().lowercase(Locale.US) }
        val columns = header.withIndex().associate { it.value to it.index }
        REQUIRED_REVIEW_COLUMNS.forEach { require(it in columns) { "Missing review column: $it" } }
        fun List<String>.value(name: String): String = getOrElse(checkNotNull(columns[name])) { "" }.trim()
        return records.drop(1).map { row ->
            ReviewDecision(
                id = row.value("id"),
                x = row.value("x").toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid review x"),
                y = row.value("y").toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid review y"),
                coordinateSpace = row.value("coordinate_space"),
                proposalClass = row.value("proposal_class"),
                proposalConfidence = row.value("proposal_confidence").toDoubleOrNull(),
                annotationSource = row.value("annotation_source"),
                currentReviewStatus = row.value("current_review_status"),
                finalClass = row.value("final_class"),
                reviewStatus = row.value("review_status"),
                reviewer = row.value("reviewer"),
                reviewedAt = row.value("reviewed_at"),
                reviewNotes = row.value("review_notes")
            )
        }
    }

    private fun validateReadOnlyFields(current: ProvisionalSourceLabel, decision: ReviewDecision) {
        require(current.x == decision.x && current.y == decision.y) {
            "Review coordinates differ for ${decision.id}"
        }
        require(current.coordinateSpace.name.equals(decision.coordinateSpace, ignoreCase = true)) {
            "Review coordinate space differs for ${decision.id}"
        }
        require(current.classification.name.equals(decision.proposalClass, ignoreCase = true)) {
            "Review proposal class differs for ${decision.id}"
        }
        require(current.confidence == decision.proposalConfidence) {
            "Review proposal confidence differs for ${decision.id}"
        }
        require(current.annotationSource.name.equals(decision.annotationSource, ignoreCase = true)) {
            "Review annotation source differs for ${decision.id}"
        }
        require(current.reviewStatus.name.equals(decision.currentReviewStatus, ignoreCase = true)) {
            "Review current status differs for ${decision.id}"
        }
    }

    private fun isAlreadyApplied(
        current: ProvisionalSourceLabel,
        decision: ReviewDecision,
        finalClass: ProvisionalSourceClass,
        finalStatus: GroundTruthReviewStatus
    ): Boolean {
        if (current.x != decision.x || current.y != decision.y) return false
        if (current.confidence != decision.proposalConfidence) return false
        val proposalClass = ProvisionalSourceClass.entries.firstOrNull {
            it.name.equals(decision.proposalClass, ignoreCase = true)
        } ?: return false
        val proposalSpace = ProvisionalCoordinateSpace.entries.firstOrNull {
            it.name.equals(decision.coordinateSpace, ignoreCase = true)
        } ?: return false
        val proposalSource = GroundTruthAnnotationSource.entries.firstOrNull {
            it.name.equals(decision.annotationSource, ignoreCase = true)
        } ?: return false
        val proposalStatus = GroundTruthReviewStatus.entries.firstOrNull {
            it.name.equals(decision.currentReviewStatus, ignoreCase = true)
        } ?: return false
        val manualStarAdjustment = proposalClass == ProvisionalSourceClass.STAR &&
            proposalSource == GroundTruthAnnotationSource.MANUAL &&
            proposalStatus == GroundTruthReviewStatus.CONFIRMED &&
            finalClass == ProvisionalSourceClass.UNCERTAIN &&
            finalStatus == GroundTruthReviewStatus.NEEDS_REVIEW
        val expectedSpace = if (finalClass == proposalClass || manualStarAdjustment) {
            proposalSpace
        } else {
            when (finalClass) {
                ProvisionalSourceClass.STAR -> ProvisionalCoordinateSpace.SKY
                ProvisionalSourceClass.SENSOR_DEFECT -> ProvisionalCoordinateSpace.CAMERA
                ProvisionalSourceClass.UNCERTAIN -> ProvisionalCoordinateSpace.UNKNOWN
                ProvisionalSourceClass.UNKNOWN -> return false
            }
        }
        val expectedSource = if (
            proposalSource == GroundTruthAnnotationSource.CATALOG && finalClass == proposalClass
        ) {
            GroundTruthAnnotationSource.CATALOG
        } else {
            GroundTruthAnnotationSource.MANUAL
        }
        return current.classification == finalClass &&
            current.coordinateSpace == expectedSpace &&
            current.annotationSource == expectedSource &&
            current.reviewStatus == finalStatus &&
            current.reviewedBy == decision.reviewer &&
            current.reviewedAt == decision.reviewedAt &&
            reviewNotesMatch(current.notes, decision.reviewNotes)
    }

    private fun isManualStarNeedsReviewAdjustment(
        current: ProvisionalSourceLabel,
        finalClass: ProvisionalSourceClass,
        finalStatus: GroundTruthReviewStatus
    ): Boolean = current.classification == ProvisionalSourceClass.STAR &&
        current.annotationSource == GroundTruthAnnotationSource.MANUAL &&
        current.reviewStatus == GroundTruthReviewStatus.CONFIRMED &&
        finalClass == ProvisionalSourceClass.UNCERTAIN &&
        finalStatus == GroundTruthReviewStatus.NEEDS_REVIEW

    private fun resolvedReviewNotes(
        currentNotes: String,
        reviewNotes: String,
        append: Boolean
    ): String = when {
        reviewNotes.isBlank() -> currentNotes
        !append || currentNotes.isBlank() -> reviewNotes
        else -> currentNotes + REVIEW_ADJUSTMENT_NOTE_PREFIX + reviewNotes
    }

    private fun reviewNotesMatch(currentNotes: String, reviewNotes: String): Boolean =
        reviewNotes.isBlank() ||
            currentNotes == reviewNotes ||
            currentNotes.endsWith(REVIEW_ADJUSTMENT_NOTE_PREFIX + reviewNotes)

    private fun parseReviewClass(value: String): ProvisionalSourceClass {
        val parsed = ProvisionalSourceClass.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        require(parsed != null && parsed != ProvisionalSourceClass.UNKNOWN) { "Invalid final class: $value" }
        return parsed
    }

    private fun parseReviewStatus(value: String): GroundTruthReviewStatus {
        val parsed = GroundTruthReviewStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        require(parsed in setOf(
            GroundTruthReviewStatus.CONFIRMED,
            GroundTruthReviewStatus.REJECTED,
            GroundTruthReviewStatus.NEEDS_REVIEW
        )) { "Invalid explicit review status: $value" }
        return checkNotNull(parsed)
    }

    private fun auditJson(
        beforeHash: String,
        afterHash: String,
        before: Map<String, ProvisionalSourceLabel>,
        replacements: Map<String, ProvisionalSourceLabel>
    ): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": \"astrophoto.ground-truth-review-audit/1\",")
        appendLine("  \"beforeSha256\": \"$beforeHash\",")
        appendLine("  \"afterSha256\": \"$afterHash\",")
        appendLine("  \"decisionCount\": ${replacements.size},")
        appendLine("  \"decisions\": [")
        replacements.entries.forEachIndexed { index, (id, after) ->
            val old = checkNotNull(before[id])
            append("    {\"id\": ${json(id)}, \"beforeClass\": ${json(old.classification.name.lowercase())}, ")
            append("\"afterClass\": ${json(after.classification.name.lowercase())}, ")
            append("\"beforeStatus\": ${json(old.reviewStatus.name.lowercase())}, ")
            append("\"afterStatus\": ${json(after.reviewStatus.name.lowercase())}, ")
            append("\"beforeSource\": ${json(old.annotationSource.name.lowercase())}, ")
            append("\"afterSource\": ${json(after.annotationSource.name.lowercase())}, ")
            append("\"reviewer\": ${json(after.reviewedBy)}, \"reviewedAt\": ${json(after.reviewedAt)}}")
            appendLine(if (index == replacements.size - 1) "" else ",")
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun writeStringAtomically(path: Path, value: String) {
        val parent = checkNotNull(path.parent)
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".review-audit-", ".tmp")
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8)
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun isIso8601(value: String): Boolean = try {
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

    private fun json(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n") + "\""

    private data class ReviewDecision(
        val id: String,
        val x: Double,
        val y: Double,
        val coordinateSpace: String,
        val proposalClass: String,
        val proposalConfidence: Double?,
        val annotationSource: String,
        val currentReviewStatus: String,
        val finalClass: String,
        val reviewStatus: String,
        val reviewer: String,
        val reviewedAt: String,
        val reviewNotes: String
    ) {
        fun isExplicit(): Boolean {
            val values = listOf(finalClass, reviewStatus, reviewer, reviewedAt, reviewNotes)
            if (values.all(String::isBlank)) return false
            require(finalClass.isNotBlank() && reviewStatus.isNotBlank() && reviewer.isNotBlank() && reviewedAt.isNotBlank()) {
                "Partial review decision for $id"
            }
            return true
        }
    }

    companion object {
        private val REQUIRED_REVIEW_COLUMNS = setOf(
            "id",
            "x",
            "y",
            "coordinate_space",
            "proposal_class",
            "proposal_confidence",
            "annotation_source",
            "current_review_status",
            "final_class",
            "review_status",
            "reviewer",
            "reviewed_at",
            "review_notes"
        )
        private const val REVIEW_ADJUSTMENT_NOTE_PREFIX = "\nReview adjustment: "
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
