package com.joe6355.astrophoto.processing.jpeg.v2.postprocessing

import com.joe6355.astrophoto.processing.jpeg.v2.composition.BackgroundMatchedStarSource
import com.joe6355.astrophoto.processing.jpeg.v2.composition.ReferenceStarRestorationPolicy
import com.joe6355.astrophoto.processing.jpeg.v2.composition.ReferenceStarSignalPreserver
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedFloatPlane
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore

/** Recover only measured baseline sources that postprocessing weakened or displaced. */
internal class ProcessedStarSignalPreserver {
    fun preserve(
        processed: FileBackedImage,
        cleanBaseline: FileBackedImage,
        referenceStars: List<DetectedStar>,
        store: ResultCandidateStore,
        originalReference: FileBackedImage? = null,
        effectiveSkyAlpha: FileBackedFloatPlane? = null
    ): FileBackedImage {
        require(processed.width == cleanBaseline.width && processed.height == cleanBaseline.height)
        if (referenceStars.isEmpty()) return processed
        val retention = ReferenceStarRetentionValidator().validate(cleanBaseline, processed, referenceStars)
        val present = retention.sources.filter { it.beforePresent }.map { it.sourceIndex }.toSet()
        val plan = ReferenceStarRestorationPolicy().plan(referenceStars, retention.sources)
        val restoreIndices = plan.sourceIndices.toMutableSet()
        var reliableIndices: Set<Int>? = null
        originalReference?.let { reference ->
            val validator = ReferenceStarRetentionValidator()
            val keptBefore = validator.validate(reference, cleanBaseline, referenceStars).sources
                .filter { it.retained }.map { it.sourceIndex }.toSet()
            reliableIndices = keptBefore
            val keptAfter = validator.validate(reference, processed, referenceStars).sources
                .filter { it.retained }.map { it.sourceIndex }.toSet()
            restoreIndices += keptBefore - keptAfter
        }
        // An absent/unmeasurable baseline source is not evidence for erasing new processed signal.
        val restore = restoreIndices.filter { it in present }.map(referenceStars::get)
        if (restore.isEmpty()) return processed
        val legacy = ReferenceStarSignalPreserver().preserve(processed, cleanBaseline, restore, store).image
        if (effectiveSkyAlpha != null) {
            val reliable = reliableIndices
            val matched = restoreIndices.filter { it in present && (reliable == null || it in reliable) }
                .map(referenceStars::get)
            val unchanged = restoreIndices.filter { it in present && reliable != null && it !in reliable }
                .map(referenceStars::get)
            var result = BackgroundMatchedStarSource.restore(processed, cleanBaseline, matched, effectiveSkyAlpha, store)
            // An intrinsically elongated or otherwise unreliable baseline is not evidence
            // for changing a source's contrast. Retain its previous exact-copy behaviour.
            if (unchanged.isNotEmpty()) {
                val next = ReferenceStarSignalPreserver().preserve(result, legacy, unchanged, store).image
                if (result !== processed && next !== result) store.deleteTemporary(result)
                result = next
            }
            val validator = ReferenceStarRetentionValidator()
            val controls = (listOf(cleanBaseline) + listOfNotNull(originalReference)).distinct()
            fun retained(reference: FileBackedImage, image: FileBackedImage) =
                validator.validate(reference, image, referenceStars).sources
                    .filter { it.retained }.map { it.sourceIndex }.toSet()
            val required = controls.map { it to retained(it, legacy) }
            fun lost(image: FileBackedImage) = required.flatMap { (reference, indices) ->
                indices - retained(reference, image)
            }.toSet()
            // Matching a local background must not cost a source preserved by the old
            // exact transfer. Repair individual stencils and recheck overlapping neighbours.
            repeat(3) {
                val missing = lost(result)
                if (missing.isEmpty()) {
                    if (legacy !== result && legacy !== processed) store.deleteTemporary(legacy)
                    return result
                }
                val next = ReferenceStarSignalPreserver().preserve(
                    result, legacy, missing.map(referenceStars::get), store
                ).image
                if (result !== processed && result !== legacy && next !== result) store.deleteTemporary(result)
                result = next
            }
            if (lost(result).isEmpty()) {
                if (legacy !== result && legacy !== processed) store.deleteTemporary(legacy)
                return result
            }
            if (result !== processed && result !== legacy) store.deleteTemporary(result)
            return legacy
        }
        return legacy
    }
}
