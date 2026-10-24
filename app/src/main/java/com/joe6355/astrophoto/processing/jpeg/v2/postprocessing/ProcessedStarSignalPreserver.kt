package com.joe6355.astrophoto.processing.jpeg.v2.postprocessing

import com.joe6355.astrophoto.processing.jpeg.v2.composition.ReferenceStarRestorationPolicy
import com.joe6355.astrophoto.processing.jpeg.v2.composition.ReferenceStarSignalPreserver
import com.joe6355.astrophoto.processing.jpeg.v2.model.DetectedStar
import com.joe6355.astrophoto.processing.jpeg.v2.quality.ReferenceStarRetentionValidator
import com.joe6355.astrophoto.processing.jpeg.v2.storage.FileBackedImage
import com.joe6355.astrophoto.processing.jpeg.v2.storage.ResultCandidateStore

/** Recover only measured baseline sources that postprocessing weakened or displaced. */
internal class ProcessedStarSignalPreserver {
    fun preserve(
        processed: FileBackedImage,
        cleanBaseline: FileBackedImage,
        referenceStars: List<DetectedStar>,
        store: ResultCandidateStore,
        originalReference: FileBackedImage? = null
    ): FileBackedImage {
        require(processed.width == cleanBaseline.width && processed.height == cleanBaseline.height)
        if (referenceStars.isEmpty()) return processed
        val retention = ReferenceStarRetentionValidator().validate(cleanBaseline, processed, referenceStars)
        val present = retention.sources.filter { it.beforePresent }.map { it.sourceIndex }.toSet()
        val plan = ReferenceStarRestorationPolicy().plan(referenceStars, retention.sources)
        val restoreIndices = plan.sourceIndices.toMutableSet()
        originalReference?.let { reference ->
            val validator = ReferenceStarRetentionValidator()
            val keptBefore = validator.validate(reference, cleanBaseline, referenceStars).sources
                .filter { it.retained }.map { it.sourceIndex }.toSet()
            val keptAfter = validator.validate(reference, processed, referenceStars).sources
                .filter { it.retained }.map { it.sourceIndex }.toSet()
            restoreIndices += keptBefore - keptAfter
        }
        // An absent/unmeasurable baseline source is not evidence for erasing new processed signal.
        val restore = restoreIndices.filter { it in present }.map(referenceStars::get)
        if (restore.isEmpty()) return processed
        return ReferenceStarSignalPreserver().preserve(
            stackedSky = processed,
            reference = cleanBaseline,
            stars = restore,
            store = store
        ).image
    }
}
