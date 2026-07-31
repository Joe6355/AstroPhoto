package com.example.astrophoto

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundTruthReviewWorkflowTest {
    @Test fun readsLegacySchema() {
        val rows = GroundTruthCsv.read(
            "# old header\nlegacy,star,10.25,12.5,sky,3,0.1,2.0,0.8,old row\n"
        )

        assertEquals(1, rows.size)
        assertEquals(ProvisionalSourceClass.STAR, rows.single().classification)
        assertEquals(10.25, rows.single().x, 0.0)
    }

    @Test fun readsNewSchemaWithReorderedColumns() {
        val rows = GroundTruthCsv.read(
            "class,id,y,x,review_status,annotation_source,coordinate_space,support_frames,confidence,notes\n" +
                "star,s1,20,10,confirmed,manual,sky,3,0.9,new row\n"
        )

        assertEquals(GroundTruthAnnotationSource.MANUAL, rows.single().annotationSource)
        assertEquals(GroundTruthReviewStatus.CONFIRMED, rows.single().reviewStatus)
    }

    @Test fun unknownEnumsAreLoadedSafelyAndExcluded() {
        val rows = GroundTruthCsv.read(
            "id,x,y,class,confidence,annotation_source,review_status,coordinate_space,support_frames\n" +
                "u1,1,2,planet,0.5,robot,maybe,galactic,1\n"
        )

        val row = rows.single()
        assertEquals(ProvisionalSourceClass.UNKNOWN, row.classification)
        assertEquals(GroundTruthAnnotationSource.UNKNOWN, row.annotationSource)
        assertEquals(GroundTruthReviewStatus.UNKNOWN, row.reviewStatus)
        assertTrue(GroundTruthEligibility.eligible(rows).isEmpty())
    }

    @Test fun migratesOnlyProvenManualIdsToConfirmed() {
        val rows = GroundTruthCsv.read(
            legacyRows("proven-star", "star"),
            LegacyGroundTruthProvenance(confirmedManualIds = setOf("proven-star"))
        )

        assertEquals(GroundTruthAnnotationSource.MANUAL, rows.single().annotationSource)
        assertEquals(GroundTruthReviewStatus.CONFIRMED, rows.single().reviewStatus)
    }

    @Test fun migratesGeneratedCandidateToAutomaticUnreviewed() {
        val rows = GroundTruthCsv.read(legacyRows("candidate-x00100-y00200", "star"))

        assertEquals(GroundTruthAnnotationSource.AUTOMATIC, rows.single().annotationSource)
        assertEquals(GroundTruthReviewStatus.UNREVIEWED, rows.single().reviewStatus)
    }

    @Test fun migratesUnknownProvenanceToNeedsReview() {
        val rows = GroundTruthCsv.read(legacyRows("plausible-manual-name", "star"))

        assertEquals(GroundTruthAnnotationSource.UNKNOWN, rows.single().annotationSource)
        assertEquals(GroundTruthReviewStatus.NEEDS_REVIEW, rows.single().reviewStatus)
    }

    @Test fun uncertainIsAlwaysExcludedFromStrictMetrics() {
        val row = label(
            id = "uncertain",
            classification = ProvisionalSourceClass.UNCERTAIN,
            source = GroundTruthAnnotationSource.MANUAL,
            status = GroundTruthReviewStatus.CONFIRMED
        )

        assertTrue(GroundTruthEligibility.eligible(listOf(row)).isEmpty())
    }

    @Test fun automaticConfirmedIsExcludedFromStrictMetrics() {
        val row = label(
            id = "auto-star",
            classification = ProvisionalSourceClass.STAR,
            source = GroundTruthAnnotationSource.AUTOMATIC,
            status = GroundTruthReviewStatus.CONFIRMED
        )

        assertFalse(GroundTruthEligibility.isEligible(row, StrictGroundTruthMetric.STAR_RETENTION))
    }

    @Test fun strictMetricsIncludeOnlyConfirmedManualOrCatalogRows() {
        val labels = listOf(
            label("manual", ProvisionalSourceClass.STAR),
            label("catalog", ProvisionalSourceClass.STAR, GroundTruthAnnotationSource.CATALOG),
            label("derived", ProvisionalSourceClass.STAR, GroundTruthAnnotationSource.DERIVED),
            label("defect", ProvisionalSourceClass.SENSOR_DEFECT)
        )

        val summary = GroundTruthEligibility.summary(labels)
        assertEquals(2, summary.eligibleConfirmedStars)
        assertEquals(1, summary.eligibleConfirmedSensorDefects)
        assertEquals(3, summary.eligibleConfirmedRows)
    }

    @Test fun stableCandidateIdsDoNotDependOnInsertionOrder() {
        val original = listOf(10.123 to 20.456, 30.0 to 40.0)
            .associate { it to GroundTruthStableIds.candidate(it.first, it.second) }
        val reversed = original.keys.reversed()
            .associate { it to GroundTruthStableIds.candidate(it.first, it.second) }

        assertEquals(original, reversed)
        assertEquals("candidate-x01012-y02046", original.getValue(10.123 to 20.456))
    }

    @Test fun csvEscapingRoundTripsCommaQuoteAndNewline() {
        val original = label("quoted", notes = "comma, quote \"value\"\nand newline")
        val decoded = GroundTruthCsv.read(GroundTruthCsv.encode(listOf(original))).single()

        assertEquals(original.notes, decoded.notes)
    }

    @Test fun nullableFieldsRoundTrip() {
        val original = label("nullable").copy(
            confidence = null,
            skyResidualPx = null,
            cameraResidualPx = null,
            reviewedBy = "",
            reviewedAt = ""
        )
        val decoded = GroundTruthCsv.read(GroundTruthCsv.encode(listOf(original))).single()

        assertEquals(null, decoded.confidence)
        assertEquals(null, decoded.skyResidualPx)
        assertEquals(null, decoded.cameraResidualPx)
    }

    @Test fun duplicateGroundTruthIdsAreRejected() {
        val encoded = GroundTruthCsv.encode(listOf(label("duplicate"), label("duplicate")))

        assertThrows(IllegalArgumentException::class.java) { GroundTruthCsv.read(encoded) }
    }

    @Test fun fixtureMigrationHasExpectedProvenanceAndStrictDenominators() {
        val fixture = fixture()
        val summary = fixture.groundTruthSummary
        val groundTruthPath = fixtureDirectory().toPath().resolve("ground-truth.csv")

        assertEquals(24, summary.totalRows)
        assertEquals(18, summary.excludedAutomaticRows)
        assertEquals(19, summary.excludedUncertainRows)
        assertEquals(18, summary.excludedUnreviewedRows)
        assertEquals(2, summary.eligibleConfirmedStars)
        assertEquals(2, summary.eligibleConfirmedSensorDefects)
        assertEquals(6, fixture.groundTruth.count {
            it.annotationSource == GroundTruthAnnotationSource.MANUAL &&
                it.reviewStatus == GroundTruthReviewStatus.CONFIRMED
        })
        assertArrayEquals(
            Files.readAllBytes(groundTruthPath),
            GroundTruthCsv.encode(fixture.groundTruth).toByteArray(StandardCharsets.UTF_8)
        )
    }

    @Test fun automaticStarIsNotInFixtureStrictDenominator() {
        val fixture = fixture()

        assertTrue(fixture.provisionalReferenceStars.any { it.id == "candidate-x55810-y22220" })
        assertFalse(fixture.strictReferenceStarLabels.any { it.id == "candidate-x55810-y22220" })
        assertEquals(listOf("star-01", "star-02"), fixture.strictReferenceStarLabels.map { it.id })
    }

    @Test fun reviewImporterRejectsDuplicateAndUnknownIds() {
        val directory = reportDirectory("duplicate-${UUID.randomUUID()}")
        val input = directory.resolve("input.csv")
        GroundTruthCsv.write(input, listOf(label("candidate-1")))
        val duplicateQueue = directory.resolve("duplicate.csv")
        Files.writeString(duplicateQueue, queue(listOf("candidate-1", "candidate-1")), StandardCharsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            GroundTruthReviewImporter().importReview(input, duplicateQueue, directory.resolve("out.csv"))
        }
        val unknownQueue = directory.resolve("unknown.csv")
        Files.writeString(unknownQueue, queue(listOf("unknown")), StandardCharsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            GroundTruthReviewImporter().importReview(input, unknownQueue, directory.resolve("out.csv"))
        }
    }

    @Test fun reviewImporterRejectsCoordinateMutationAndPartialReview() {
        val directory = reportDirectory("coordinates-${UUID.randomUUID()}")
        val input = directory.resolve("input.csv")
        GroundTruthCsv.write(input, listOf(label("candidate-1", source = GroundTruthAnnotationSource.AUTOMATIC, status = GroundTruthReviewStatus.UNREVIEWED)))
        val changed = directory.resolve("changed.csv")
        Files.writeString(changed, queue(listOf("candidate-1"), x = "11"), StandardCharsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            GroundTruthReviewImporter().importReview(input, changed, directory.resolve("out.csv"))
        }
        val partial = directory.resolve("partial.csv")
        Files.writeString(partial, queue(listOf("candidate-1"), reviewer = "reviewer", reviewedAt = ""), StandardCharsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            GroundTruthReviewImporter().importReview(input, partial, directory.resolve("out.csv"))
        }
    }

    @Test fun reviewImporterRejectsConflictWithConfirmedManualClass() {
        val directory = reportDirectory("conflict-${UUID.randomUUID()}")
        val input = directory.resolve("input.csv")
        GroundTruthCsv.write(input, listOf(label("manual-star")))
        val queue = directory.resolve("queue.csv")
        Files.writeString(
            queue,
            queue(listOf("manual-star"), finalClass = "sensor_defect"),
            StandardCharsets.UTF_8
        )

        assertThrows(IllegalArgumentException::class.java) {
            GroundTruthReviewImporter().importReview(input, queue, directory.resolve("out.csv"))
        }
    }

    @Test fun validExplicitImportIsAtomicAuditedAndIdempotent() {
        val directory = reportDirectory("import-${UUID.randomUUID()}")
        val input = directory.resolve("input.csv")
        val automatic = label(
            "candidate-1",
            source = GroundTruthAnnotationSource.AUTOMATIC,
            status = GroundTruthReviewStatus.UNREVIEWED
        )
        GroundTruthCsv.write(input, listOf(automatic))
        val queue = directory.resolve("queue.csv")
        Files.writeString(queue, queue(listOf("candidate-1")), StandardCharsets.UTF_8)
        val importer = GroundTruthReviewImporter()
        val first = importer.importReview(input, queue, directory.resolve("first.csv"))
        val second = importer.importReview(input, queue, directory.resolve("second.csv"))

        assertArrayEquals(Files.readAllBytes(first.output), Files.readAllBytes(second.output))
        val imported = GroundTruthCsv.read(first.output.toFile()).single()
        assertEquals(GroundTruthAnnotationSource.MANUAL, imported.annotationSource)
        assertEquals(GroundTruthReviewStatus.CONFIRMED, imported.reviewStatus)
        assertEquals("fixture-reviewer", imported.reviewedBy)
        assertTrue(Files.isRegularFile(first.auditLog))
        assertEquals(first.outputSha256, sha256(first.output))
        assertThrows(IllegalArgumentException::class.java) {
            importer.importReview(input, queue, input)
        }
    }

    @Test fun invalidImportLeavesExistingOutputUntouched() {
        val directory = reportDirectory("atomic-${UUID.randomUUID()}")
        val input = directory.resolve("input.csv")
        GroundTruthCsv.write(input, listOf(label("candidate-1")))
        val output = directory.resolve("out.csv")
        Files.writeString(output, "sentinel", StandardCharsets.UTF_8)
        val invalid = directory.resolve("invalid.csv")
        Files.writeString(invalid, queue(listOf("unknown")), StandardCharsets.UTF_8)

        assertThrows(IllegalArgumentException::class.java) {
            GroundTruthReviewImporter().importReview(input, invalid, output)
        }
        assertEquals("sentinel", Files.readString(output, StandardCharsets.UTF_8))
    }

    @Test fun reviewPackageIsCompleteDeterministicAndContainsNoSourcePhotos() {
        val fixture = fixture()
        val bundle = diagnosticBundle()
        val generator = GroundTruthReviewPackageGenerator()
        val groundTruth = fixtureDirectory().toPath().resolve("ground-truth.csv")
        val first = generator.generate(
            fixture,
            bundle,
            groundTruth,
            reportDirectory("package-a")
        )
        val second = generator.generate(
            fixture,
            bundle,
            groundTruth,
            reportDirectory("package-b")
        )

        assertEquals(first.manifestSha256, second.manifestSha256)
        assertEquals(first.queueSha256, second.queueSha256)
        val firstHashes = packageHashes(first.manifest.parent)
        val secondHashes = packageHashes(second.manifest.parent)
        assertEquals(firstHashes, secondHashes)
        listOf(
            "review-manifest.json",
            "review-queue.csv",
            "candidates-contact-sheet.png",
            "reference-overlay.png",
            "camera-space-recurrence.png",
            "sky-space-aligned.png",
            "alignment-before-after.png",
            "sensor-mask-overlay.png",
            "index.html",
            "human-review/index.html",
            "human-review/README_RU.md",
            "human-review/review-decisions-template.csv",
            "human-review/candidate-summary.csv",
            "human-review/contact-sheet.png",
            "human-review/evidence-manifest.json",
            "human-review/manual-review-bundle.zip",
            "human-review/sensor-mask-global.png"
        ).forEach { assertTrue("Missing $it", Files.isRegularFile(first.manifest.parent.resolve(it))) }
        assertTrue(Files.isDirectory(first.manifest.parent.resolve("crops/native")))
        assertTrue(Files.isDirectory(first.manifest.parent.resolve("crops/nearest")))
        assertTrue(Files.isDirectory(first.manifest.parent.resolve("crops/grid")))
        assertTrue(Files.isDirectory(first.manifest.parent.resolve("crops/alignment-before")))
        assertTrue(Files.isDirectory(first.manifest.parent.resolve("crops/alignment-after")))
        assertTrue(Files.isDirectory(first.manifest.parent.resolve("crops/mask")))
        assertFalse(packageHashes(first.manifest.parent).keys.any {
            it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".dng")
        })
        ZipFile(first.manifest.parent.resolve("human-review/manual-review-bundle.zip").toFile()).use { archive ->
            val names = archive.entries().asSequence().map { it.name.lowercase() }.toList()
            assertTrue(names.isNotEmpty())
            assertFalse(names.any {
                it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".dng") || it.endsWith(".apk")
            })
        }
    }

    @Test fun reviewQueueOrderMatchesDeterministicCandidateOrder() {
        val fixture = fixture()
        val bundle = diagnosticBundle()
        val result = GroundTruthReviewPackageGenerator().generate(
            fixture,
            bundle,
            fixtureDirectory().toPath().resolve("ground-truth.csv"),
            reportDirectory("queue-order")
        )
        val records = CsvCodec.parse(Files.readString(result.queue, StandardCharsets.UTF_8))
            .filterNot { it.firstOrNull()?.startsWith('#') == true }
        val ids = records.drop(1).map { it.first() }
        val expected = bundle.candidates.sortedWith(
            compareBy<Stage6CandidateDiagnostic> { it.referenceY }
                .thenBy { it.referenceX }
                .thenBy { it.id }
        ).map { it.id }

        assertEquals(expected, ids)
        assertEquals(ids.distinct(), ids)
    }

    @Test fun automaticMaskArtifactUsesPersistentEvidenceAndReferenceMapping() {
        val fixture = fixture()
        val bundle = diagnosticBundle()
        val mask = requireNotNull(bundle.sensorDefectMask)

        assertTrue(mask.enabled)
        assertEquals(fixture.frames.first().width, mask.width)
        assertEquals(fixture.frames.first().height, mask.height)
        assertEquals(10, mask.regions.size)
        assertEquals(210, mask.footprintPixels.size)
        fixture.strictSensorDefects.forEach { defect ->
            assertTrue(mask.contains(defect.x.roundToInt(), defect.y.roundToInt()))
        }
        val reference = bundle.frames[bundle.referenceFrameIndex]
        assertTrue(reference.cleanAccepted)
        assertEquals(
            com.example.astrophoto.processing.jpeg.v2.model.ReferenceToSourceTransform.Identity,
            reference.cleanTransform
        )

        val packageRoot = generatedPackage("mask-mapping")
        val human = packageRoot.resolve("human-review")
        val manifest = Files.readString(human.resolve("evidence-manifest.json"), StandardCharsets.UTF_8)
        assertTrue(manifest.contains("\"maskActive\": true"))
        assertTrue(manifest.contains("\"maskRegionCount\": 10"))
        val global = ImageIO.read(human.resolve("sensor-mask-global.png").toFile())
        assertEquals(mask.width, global.width)
        assertEquals(mask.height, global.height)
        fixture.strictSensorDefects.forEach { defect ->
            assertEquals(0xFFFFFF, global.getRGB(defect.x.roundToInt(), defect.y.roundToInt()) and 0xFFFFFF)
        }
    }

    @Test fun rawEvidenceUsesUnannotatedPixelsAndExactTransforms() {
        val fixture = fixture()
        val bundle = diagnosticBundle()
        val packageRoot = generatedPackage("raw-transform")
        val human = packageRoot.resolve("human-review")
        val candidates = automaticReviewCandidates()
        val candidate = candidates.first()
        val directory = human.resolve("candidates/${candidate.id}")
        val raw = ImageIO.read(directory.resolve("raw-reference.png").toFile())
        assertEquals(GroundTruthReviewEvidenceGenerator.CROP_SIZE, raw.width)
        assertEquals(GroundTruthReviewEvidenceGenerator.CROP_SIZE, raw.height)
        assertTrue(raw.width % 2 == 1 && raw.height % 2 == 1)
        val radius = GroundTruthReviewEvidenceGenerator.CROP_SIZE / 2
        for (y in 0 until raw.height) for (x in 0 until raw.width) {
            val sourceX = (candidate.referenceX + x - radius).roundToInt()
            val sourceY = (candidate.referenceY + y - radius).roundToInt()
            assertEquals(
                fixture.frames[fixture.referenceFrameIndex].pixelAt(sourceX, sourceY),
                raw.getRGB(x, y)
            )
        }

        val summary = summaryRows(human.resolve("candidate-summary.csv"))
        val row = summary.first { it[column(summary, "id")] == candidate.id }
        val selected = row[column(summary, "selected_accepted_frames")]
            .split(';').map(String::toInt)
        assertEquals(GroundTruthReviewEvidenceGenerator.FRAME_SELECTION_COUNT, selected.size)
        assertTrue(selected.all { bundle.frames[it - 1].cleanAccepted })
        assertEquals(1, selected.first())
        assertTrue(bundle.referenceFrameIndex + 1 in selected)
        assertEquals(25, selected.last())

        val cameraSprite = ImageIO.read(directory.resolve("camera-space-raw-sprite.png").toFile())
        val skySprite = ImageIO.read(directory.resolve("sky-space-raw-sprite.png").toFile())
        selected.forEachIndexed { tile, frameIndex ->
            val frame = fixture.frames[frameIndex - 1]
            val cameraExpected = frame.pixelAt(
                candidate.referenceX.roundToInt(),
                candidate.referenceY.roundToInt()
            )
            assertEquals(cameraExpected, cameraSprite.getRGB(tile * raw.width + radius, radius))
            val mapped = bundle.frames[frameIndex - 1].cleanTransform.mapOutputToSource(
                candidate.referenceX.toFloat(),
                candidate.referenceY.toFloat()
            )
            val skyExpected = frame.pixelAt(mapped.x.roundToInt(), mapped.y.roundToInt())
            assertEquals(skyExpected, skySprite.getRGB(tile * raw.width + radius, radius))
        }
    }

    @Test fun unavailableMaskIsExplicitAndNeverRenderedAsNegativeEvidence() {
        val fixture = fixture()
        val bundle = diagnosticBundle().copy(sensorDefectMask = null)
        val candidates = bundle.candidates.sortedWith(
            compareBy<Stage6CandidateDiagnostic> { it.referenceY }
                .thenBy { it.referenceX }
                .thenBy { it.id }
        )
        val labelsById = fixture.groundTruth.associateBy { it.id }
        val labels = candidates.map { checkNotNull(labelsById[it.id]) }
        val result = GroundTruthReviewEvidenceGenerator().generate(
            fixture,
            bundle,
            candidates,
            labels,
            reportDirectory("mask-unavailable").resolve("human-review")
        )

        assertFalse(result.maskActive)
        assertEquals("bundle_sensor_defect_mask_null", result.maskUnavailableReason)
        val html = Files.readString(result.outputRoot.resolve("index.html"), StandardCharsets.UTF_8)
        val manifest = Files.readString(result.manifest, StandardCharsets.UTF_8)
        assertTrue(html.contains("MASK DATA UNAVAILABLE"))
        assertTrue(manifest.contains("\"maskActive\": false"))
        assertTrue(Files.isRegularFile(result.outputRoot.resolve("mask-data-unavailable.png")))
        automaticReviewCandidates().forEach { candidate ->
            assertTrue(
                Files.isRegularFile(
                    result.outputRoot.resolve("candidates/${candidate.id}/mask-unavailable.png")
                )
            )
        }
    }

    @Test fun interactiveHtmlExportsOnlyExplicitDecisions() {
        val fixture = fixture()
        val beforeHash = sha256(fixtureDirectory().toPath().resolve("ground-truth.csv"))
        val packageRoot = generatedPackage("interactive-html")
        val human = packageRoot.resolve("human-review")
        val html = Files.readString(human.resolve("index.html"), StandardCharsets.UTF_8)
        val template = CsvCodec.parse(
            Files.readString(human.resolve("review-decisions-template.csv"), StandardCharsets.UTF_8)
        )

        assertTrue(html.contains("STAR:['star','confirmed']"))
        assertTrue(html.contains("SENSOR_DEFECT:['sensor_defect','confirmed']"))
        assertTrue(html.contains("UNCERTAIN:['uncertain','needs_review']"))
        assertTrue(html.contains("REJECTED:['uncertain','rejected']"))
        assertTrue(html.contains("reviewed_at:new Date().toISOString()"))
        assertTrue(html.contains("replaceAll('\\\"','\\\"\\\"')"))
        assertTrue(html.contains("if(!d){rows.push([id,'','','','',''])"))
        assertEquals(19, template.size)
        assertEquals(
            listOf("id", "final_class", "review_status", "reviewed_by", "reviewed_at", "notes"),
            template.first()
        )
        template.drop(1).forEach { row -> assertTrue(row.drop(1).all(String::isBlank)) }
        assertEquals(beforeHash, sha256(fixtureDirectory().toPath().resolve("ground-truth.csv")))
        assertEquals(2, fixture.groundTruthSummary.eligibleConfirmedStars)
        assertEquals(2, fixture.groundTruthSummary.eligibleConfirmedSensorDefects)
        assertEquals(18, fixture.groundTruth.count {
            it.annotationSource == GroundTruthAnnotationSource.AUTOMATIC &&
                it.reviewStatus == GroundTruthReviewStatus.UNREVIEWED
        })
        assertFalse(fixture.groundTruth.any {
            it.annotationSource == GroundTruthAnnotationSource.AUTOMATIC &&
                it.reviewStatus == GroundTruthReviewStatus.CONFIRMED
        })
    }

    private fun generatedPackage(name: String): Path {
        val fixture = fixture()
        return GroundTruthReviewPackageGenerator().generate(
            fixture,
            diagnosticBundle(),
            fixtureDirectory().toPath().resolve("ground-truth.csv"),
            reportDirectory(name)
        ).manifest.parent
    }

    private fun automaticReviewCandidates(): List<Stage6CandidateDiagnostic> {
        val labels = fixture().groundTruth.associateBy { it.id }
        return diagnosticBundle().candidates.sortedWith(
            compareBy<Stage6CandidateDiagnostic> { it.referenceY }
                .thenBy { it.referenceX }
                .thenBy { it.id }
        ).filter { candidate ->
            val label = checkNotNull(labels[candidate.id])
            label.annotationSource == GroundTruthAnnotationSource.AUTOMATIC &&
                label.reviewStatus == GroundTruthReviewStatus.UNREVIEWED
        }
    }

    private fun summaryRows(path: Path): List<List<String>> = CsvCodec.parse(
        Files.readString(path, StandardCharsets.UTF_8)
    )

    private fun column(rows: List<List<String>>, name: String): Int =
        rows.first().indexOf(name).also { require(it >= 0) }

    private fun queue(
        ids: List<String>,
        x: String = "10",
        finalClass: String = "star",
        reviewer: String = "fixture-reviewer",
        reviewedAt: String = "2026-07-31T12:00:00Z"
    ): String = buildString {
        appendLine(
            CsvCodec.encodeRow(
                listOf(
                    "id", "x", "y", "coordinate_space", "proposal_class",
                    "proposal_confidence", "annotation_source", "current_review_status", "final_class",
                    "review_status", "reviewer", "reviewed_at", "review_notes"
                )
            )
        )
        ids.forEach { id ->
            val automatic = id.startsWith("candidate-")
            appendLine(
                CsvCodec.encodeRow(
                    listOf(
                        id,
                        x,
                        "20",
                        "sky",
                        "star",
                        "0.9",
                        if (automatic) "automatic" else "manual",
                        if (automatic) "unreviewed" else "confirmed",
                        finalClass,
                        "confirmed",
                        reviewer,
                        reviewedAt,
                        "explicit review"
                    )
                )
            )
        }
    }

    private fun label(
        id: String,
        classification: ProvisionalSourceClass = ProvisionalSourceClass.STAR,
        source: GroundTruthAnnotationSource = GroundTruthAnnotationSource.MANUAL,
        status: GroundTruthReviewStatus = GroundTruthReviewStatus.CONFIRMED,
        notes: String = "test"
    ): ProvisionalSourceLabel = ProvisionalSourceLabel(
        id = id,
        classification = classification,
        x = 10.0,
        y = 20.0,
        coordinateSpace = if (classification == ProvisionalSourceClass.SENSOR_DEFECT) {
            ProvisionalCoordinateSpace.CAMERA
        } else {
            ProvisionalCoordinateSpace.SKY
        },
        supportFrames = 3,
        skyResidualPx = 0.1,
        cameraResidualPx = 2.0,
        confidence = 0.9,
        annotationSource = source,
        reviewStatus = status,
        reviewedBy = "",
        reviewedAt = "",
        notes = notes
    )

    private fun legacyRows(id: String, classification: String): String =
        "$id,$classification,10,20,sky,3,0.1,2,0.9,legacy\n"

    private fun fixture(): Stage6RegressionFixture = cachedFixture

    private fun diagnosticBundle(): Stage6DiagnosticBundle = cachedBundle

    private fun fixtureDirectory(): File {
        val resource = requireNotNull(
            requireNotNull(javaClass.classLoader)
                .getResource("jpeg-stage6/urban-window-30/manifest.properties")
        )
        return requireNotNull(File(resource.toURI()).parentFile)
    }

    private fun reportDirectory(name: String): Path {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .map { it.toPath().toAbsolutePath().normalize() }
            .first { Files.isDirectory(it.resolve("app")) }
        val directory = root.resolve("app/build/reports/ground-truth-review-tests/$name")
        Files.createDirectories(directory)
        return directory
    }

    private fun packageHashes(root: Path): Map<String, String> = Files.walk(root).use { stream ->
        stream.filter(Files::isRegularFile)
            .sorted(compareBy { root.relativize(it).toString() })
            .toList()
            .associate { root.relativize(it).toString().replace('\\', '/') to sha256(it) }
    }

    companion object {
        private val cachedFixture: Stage6RegressionFixture by lazy {
            val resource = requireNotNull(
                requireNotNull(GroundTruthReviewWorkflowTest::class.java.classLoader)
                    .getResource("jpeg-stage6/urban-window-30/manifest.properties")
            )
            Stage6RegressionFixtureLoader.load(requireNotNull(File(resource.toURI()).parentFile))
        }
        private val cachedBundle: Stage6DiagnosticBundle by lazy {
            Stage6CandidateDiagnosticRunner().analyze(cachedFixture)
        }
    }
}

class GroundTruthReviewCommandTest {
    @Test fun generateConfiguredReviewPackage() {
        val output = System.getenv(GENERATE_OUTPUT_ENV)?.takeIf(String::isNotBlank) ?: return
        val fixtureDirectory = System.getenv(FIXTURE_ENV)?.takeIf(String::isNotBlank)?.let(::File)
            ?: resourceFixtureDirectory()
        val fixture = Stage6RegressionFixtureLoader.load(fixtureDirectory)
        val result = GroundTruthReviewPackageGenerator().generate(
            fixture,
            Stage6CandidateDiagnosticRunner().analyze(fixture),
            fixtureDirectory.toPath().resolve("ground-truth.csv"),
            Path.of(output)
        )
        println("GROUND_TRUTH_REVIEW_MANIFEST_SHA256=${result.manifestSha256}")
        println("GROUND_TRUTH_REVIEW_QUEUE_SHA256=${result.queueSha256}")
    }

    @Test fun importConfiguredReviewQueue() {
        val input = System.getenv(IMPORT_INPUT_ENV)?.takeIf(String::isNotBlank) ?: return
        val review = requireNotNull(System.getenv(IMPORT_QUEUE_ENV)?.takeIf(String::isNotBlank))
        val output = requireNotNull(System.getenv(IMPORT_OUTPUT_ENV)?.takeIf(String::isNotBlank))
        val audit = System.getenv(IMPORT_AUDIT_ENV)?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: Path.of(output + ".audit.json")
        val result = GroundTruthReviewImporter().importReview(
            Path.of(input),
            Path.of(review),
            Path.of(output),
            audit
        )
        println("GROUND_TRUTH_IMPORT_DECISIONS=${result.importedDecisionCount}")
        println("GROUND_TRUTH_IMPORT_SHA256=${result.outputSha256}")
    }

    private fun resourceFixtureDirectory(): File {
        val resource = requireNotNull(
            requireNotNull(javaClass.classLoader)
                .getResource("jpeg-stage6/urban-window-30/manifest.properties")
        )
        return requireNotNull(File(resource.toURI()).parentFile)
    }

    companion object {
        private const val FIXTURE_ENV = "ASTROPHOTO_GROUND_TRUTH_FIXTURE_DIR"
        private const val GENERATE_OUTPUT_ENV = "ASTROPHOTO_GROUND_TRUTH_REVIEW_OUTPUT_DIR"
        private const val IMPORT_INPUT_ENV = "ASTROPHOTO_GROUND_TRUTH_IMPORT_INPUT"
        private const val IMPORT_QUEUE_ENV = "ASTROPHOTO_GROUND_TRUTH_IMPORT_QUEUE"
        private const val IMPORT_OUTPUT_ENV = "ASTROPHOTO_GROUND_TRUTH_IMPORT_OUTPUT"
        private const val IMPORT_AUDIT_ENV = "ASTROPHOTO_GROUND_TRUTH_IMPORT_AUDIT"
    }
}
