package com.example.astrophoto.processing.jpeg.v2.registration

data class FullResolutionRejectedPatch(
    val patch: FullResolutionStarPatch,
    val reason: String
)

data class FullResolutionPatchSelection(
    val selected: List<FullResolutionStarPatch>,
    val rejected: List<FullResolutionRejectedPatch>
)

/** Shared bounded selection used by the Stage 11 diagnostic and Stage 12 centroid authority. */
class FullResolutionStarPatchSelector {
    fun select(patches: List<FullResolutionStarPatch>): FullResolutionPatchSelection {
        val rejected = mutableListOf<FullResolutionRejectedPatch>()
        val eligible = patches.filter { patch ->
            val reason = when {
                patch.motionCluster == TemporalMotionCluster.STATIONARY_CAMERA_SPACE ->
                    "stationary_camera_patch"
                patch.skyCoverage < MIN_SKY_COVERAGE -> "sky_foreground_boundary_patch"
                patch.confidence < MIN_STAR_CONFIDENCE -> "low_confidence_star_patch"
                patch.localContrast <= 0f -> "low_contrast_star_patch"
                patch.ellipticity > MAX_STAR_ELLIPTICITY -> "line_or_building_edge_patch"
                else -> null
            }
            if (reason != null) rejected += FullResolutionRejectedPatch(patch, reason)
            reason == null
        }
        val bySector = eligible.groupBy { it.sector }.toSortedMap().mapValues { (_, values) ->
            values.sortedWith(
                compareByDescending<FullResolutionStarPatch> {
                    if (it.motionCluster == TemporalMotionCluster.COHERENT_MOVING_SKY) 1 else 0
                }.thenByDescending { it.confidence * (1f + it.localContrast.coerceAtLeast(0f)) }
                    .thenBy { it.y }.thenBy { it.x }
            ).toMutableList()
        }
        val selected = mutableListOf<FullResolutionStarPatch>()
        while (selected.size < MAX_PATCHES && bySector.values.any { it.isNotEmpty() }) {
            bySector.values.forEach { sector ->
                if (selected.size < MAX_PATCHES && sector.isNotEmpty()) selected += sector.removeAt(0)
            }
        }
        val selectedKeys = selected.map(::patchKey).toSet()
        eligible.filter { patchKey(it) !in selectedKeys }.forEach { patch ->
            rejected += FullResolutionRejectedPatch(patch, "patch_budget_exceeded")
        }
        return FullResolutionPatchSelection(selected, rejected)
    }

    private fun patchKey(patch: FullResolutionStarPatch): Pair<Int, Int> =
        (patch.x * 100f).toInt() to (patch.y * 100f).toInt()

    companion object {
        private const val MAX_PATCHES = 48
        private const val MIN_SKY_COVERAGE = 0.92f
        private const val MIN_STAR_CONFIDENCE = 0.25f
        private const val MAX_STAR_ELLIPTICITY = 0.80f
    }
}
