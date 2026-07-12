package com.example.astrophoto

fun selectEligibleLightFrames(
    frames: List<SessionFrame>,
    marks: FrameMarks,
    favoritesOnly: Boolean
): List<SessionFrame> = frames.filter { frame ->
    frame.category == SessionFrameCategory.LIGHTS_JPEG &&
        frame.key !in marks.bad &&
        frame.key !in marks.autoBad &&
        (!favoritesOnly || frame.key in marks.favorite)
}
