package com.example.astrophoto.processing.jpeg.v2.registration

data class CaptureSequenceFrame(
    val id: String,
    val fileName: String,
    val createdAtMillis: Long
)

object CaptureSequenceIndexResolver {
    fun resolve(frames: List<CaptureSequenceFrame>): Map<String, Int> {
        val parsed = frames.map { frame -> frame to captureNumber(frame.fileName) }
        val parsedValues = parsed.mapNotNull { it.second }
        if (parsedValues.size == frames.size && parsedValues.distinct().size == frames.size) {
            return parsed.associate { (frame, captureIndex) ->
                frame.id to checkNotNull(captureIndex)
            }
        }
        return frames.sortedWith(
            compareBy<CaptureSequenceFrame> { it.createdAtMillis }
                .thenBy { it.fileName }
                .thenBy { it.id }
        ).mapIndexed { index, frame -> frame.id to index + 1 }.toMap()
    }

    private fun captureNumber(fileName: String): Int? =
        Regex("_(\\d{3})\\.(?:jpg|jpeg)$", RegexOption.IGNORE_CASE)
            .find(fileName)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
}
