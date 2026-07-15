package com.example.astrophoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingProfileTest {
    private val profiles = AstroProcessingProfile.entries.filterNot { it == AstroProcessingProfile.NORMAL }

    @Test
    fun allFiveExpectedProfilesExist() {
        assertEquals(
            listOf(
                AstroProcessingProfile.DEEP_SKY,
                AstroProcessingProfile.DEEP_SKY_ALIGNED,
                AstroProcessingProfile.URBAN_SKY,
                AstroProcessingProfile.URBAN_SKY_STRONG,
                AstroProcessingProfile.MAX_STARS
            ),
            profiles
        )
    }

    @Test
    fun alignedProfileExplicitlyRequiresAlignment() {
        assertTrue(profileRecipe(AstroProcessingProfile.DEEP_SKY_ALIGNED, 6).requiresAlignment)
        assertFalse(profileRecipe(AstroProcessingProfile.DEEP_SKY, 6).requiresAlignment)
    }

    @Test
    fun maximumStarsUsesSignalPreservingSigmaAndAggressiveAlignment() {
        val recipe = profileRecipe(AstroProcessingProfile.MAX_STARS, 8)
        assertTrue(recipe.useSignalPreservingSigma)
        assertTrue(recipe.aggressiveAlignment)
        assertEquals(StarBoostMode.STRONG, recipe.starBoostMode)
    }

    @Test
    fun cityStrongIsStrongerThanNormalWithoutChangingOrder() {
        val normal = profileRecipe(AstroProcessingProfile.URBAN_SKY, 8)
        val strong = profileRecipe(AstroProcessingProfile.URBAN_SKY_STRONG, 8)
        assertEquals(BackgroundRemovalMode.URBAN, normal.backgroundMode)
        assertEquals(BackgroundRemovalMode.URBAN, strong.backgroundMode)
        assertEquals(AstroStretchMode.NATURAL, normal.stretchMode)
        assertEquals(AstroStretchMode.NATURAL, strong.stretchMode)
        assertEquals(StarBoostMode.SOFT, normal.starBoostMode)
        assertEquals(StarBoostMode.STRONG, strong.starBoostMode)
    }

    @Test
    fun smallStacksDoNotBoostUnverifiedDetail() {
        profiles.filter { it.minimumFrames < 6 }.forEach { profile ->
            for (frameCount in profile.minimumFrames until 6) {
                val smallStack = profileRecipe(profile, frameCount)
                assertFalse(
                    "$profile must not use sigma at $frameCount frames",
                    smallStack.useSignalPreservingSigma
                )
                assertEquals(
                    "$profile must not boost noisy detail at $frameCount frames",
                    StarBoostMode.OFF,
                    smallStack.starBoostMode
                )
            }
        }

        profiles.forEach { profile ->
            val stableStack = profileRecipe(profile, 6)
            assertTrue("$profile must use signal-preserving sigma from 6 frames", stableStack.useSignalPreservingSigma)
            assertTrue(
                "$profile may boost only after signal-preserving stacking",
                stableStack.starBoostMode != StarBoostMode.OFF
            )
        }
    }

    @Test
    fun profileOutputsRemainValidNonCollapsedAndMeaningfullyChanged() {
        profiles.forEach { profile ->
            val input = syntheticUrbanSky()
            val snapshot = input.pixels.copyOf()
            val output = input.copy(pixels = input.pixels.copyOf())
            applyLegacyProfilePostProcessingInPlace(output, profile, 8)
            assertEquals(input.width, output.width)
            assertEquals(input.height, output.height)
            assertTrue("$profile alpha", output.pixels.all { it ushr 24 and 0xFF == 255 })
            assertFalse("$profile unchanged", snapshot.contentEquals(output.pixels))
            val metrics = analyzeProfileImage(
                output,
                profileRecipe(profile, 8).roi,
                profileRecipe(profile, 8).sensitivity
            )
            assertTrue("$profile black collapse", metrics.blackPercent < 98f)
            assertTrue("$profile white collapse", metrics.whitePercent < 98f)
            assertTrue("$profile uniform", metrics.dynamicRange > 1)
            assertArrayEquals("$profile input mutation", snapshot, input.pixels)
        }
    }

    @Test
    fun profilePostProcessingIsDeterministic() {
        profiles.forEach { profile ->
            val first = syntheticUrbanSky()
            val second = first.copy(pixels = first.pixels.copyOf())
            applyLegacyProfilePostProcessingInPlace(first, profile, 8)
            applyLegacyProfilePostProcessingInPlace(second, profile, 8)
            assertArrayEquals("$profile", first.pixels, second.pixels)
        }
    }

    @Test
    fun cityProfilesReduceWarmCastWithoutTurningBlue() {
        listOf(AstroProcessingProfile.URBAN_SKY, AstroProcessingProfile.URBAN_SKY_STRONG)
            .forEach { profile ->
                val image = syntheticUrbanSky()
                val before = averageChannels(image)
                applyLegacyProfilePostProcessingInPlace(image, profile, 8)
                val after = averageChannels(image)
                assertTrue(after.first - after.third < before.first - before.third)
                assertTrue(after.first >= after.third - 20)
            }
    }

    @Test
    fun brightWindowDoesNotCreateStarsAcrossImage() {
        profiles.forEach { profile ->
            val image = syntheticUrbanSky()
            applyLegacyProfilePostProcessingInPlace(image, profile, 8)
            val recipe = profileRecipe(profile, 8)
            val stars = StarDetector().detect(image, recipe.roi, recipe.sensitivity).stars
            assertTrue("$profile false stars=${stars.size}", stars.size < 30)
        }
    }

    @Test
    fun everyProfileAlignmentFollowsMovingSkyInsteadOfStaticForeground() {
        profiles.forEach { profile ->
            val recipe = profileRecipe(profile, 8)
            val result = alignManualImages(
                alignmentScene(0, 0),
                alignmentScene(4, 3),
                safeMode = !recipe.aggressiveAlignment,
                maxShiftPx = 8,
                roi = recipe.alignmentRoi
            )
            assertEquals("$profile method", "stars", result.method)
            assertEquals("$profile shift", 4 to 3, result.shift.dx to result.shift.dy)
        }
    }

    @Test
    fun croppedProfileInputExcludesForegroundAndKeepsSkyAlignment() {
        val crop = NormalizedCropRect(0f, 0f, 1f, 0.65f)
        profiles.forEach { profile ->
            val recipe = profileRecipe(profile, 8)
            val result = alignManualImages(
                cropArgbImage(alignmentScene(0, 0), crop),
                cropArgbImage(alignmentScene(4, 3), crop),
                safeMode = !recipe.aggressiveAlignment,
                maxShiftPx = 8,
                roi = recipe.alignmentRoi
            )
            assertEquals("$profile cropped shift", 4 to 3, result.shift.dx to result.shift.dy)
        }
    }

    @Test
    fun everyEnabledProfileHasMeaningfullyDifferentConfiguration() {
        assertEquals(profiles.size, profiles.map { profileRecipe(it, 8) }.toSet().size)
    }

    @Test
    fun deviceLikeDarkSceneDoesNotReceiveExtremeMedianLiftOrLoseStarContrast() {
        profiles.forEach { profile ->
            val input = syntheticDeviceDarkScene()
            val recipe = profileRecipe(profile, 8)
            val before = analyzeProfileImage(input, recipe.roi, recipe.sensitivity)
            val output = input.copy(pixels = input.pixels.copyOf())
            applyLegacyProfilePostProcessingInPlace(output, profile, 8)
            val after = analyzeProfileImage(output, recipe.roi, recipe.sensitivity)
            val sanity = evaluateProfileSanity(before, after)
            assertTrue("$profile: $sanity", sanity is ProfileSanityResult.Passed)
            assertTrue(
                "$profile median ${before.medianLuminance}->${after.medianLuminance}",
                after.medianLuminance <= before.medianLuminance +
                    ProfileSanityThresholds.MAX_MEDIAN_LIFT_ABSOLUTE
            )
            if (before.medianStarContrast > 0f) {
                assertTrue(
                    "$profile star contrast ${before.medianStarContrast}->${after.medianStarContrast}",
                    after.medianStarContrast >= before.medianStarContrast *
                        ProfileSanityThresholds.MIN_MEDIAN_CONTRAST_FRACTION
                )
            }
        }
    }

    @Test
    fun everySignalProfileRejectsAnIsolatedHotPixel() {
        profiles.forEach { profile ->
            val recipe = profileRecipe(profile, 8)
            assertTrue("$profile must use verified signal Sigma at 8 frames", recipe.useSignalPreservingSigma)
            val frames = List(7) { ArgbPixelImage(1, 1, intArrayOf(rgb(8, 10, 12))) } +
                ArgbPixelImage(1, 1, intArrayOf(rgb(255, 255, 255)))
            assertEquals(rgb(8, 10, 12), signalPreservingSigmaStack(frames, recipe.sigma).pixels.single())
        }
    }

    private fun syntheticUrbanSky(): ArgbPixelImage {
        val width = 64
        val height = 64
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            pixels[y * width + x] = rgb(45 + x / 2, 30 + x / 3, 20 + x / 5)
        }
        listOf(8 to 8, 18 to 20, 30 to 12, 42 to 28, 53 to 16, 24 to 36).forEachIndexed { i, (x, y) ->
            pixels[y * width + x] = rgb(100 + i * 15, 105 + i * 14, 115 + i * 12)
        }
        for (y in 48..58) for (x in 44..58) pixels[y * width + x] = rgb(210, 155, 80)
        return ArgbPixelImage(width, height, pixels)
    }

    private fun alignmentScene(dx: Int, dy: Int): ArgbPixelImage {
        val width = 64
        val height = 64
        val pixels = IntArray(width * height) { rgb(12, 12, 12) }
        for (x in 0 until width) pixels[41 * width + x] = rgb(150, 150, 150)
        for (y in 44 until height) for (x in 0 until width) {
            val value = 100 + ((x / 5) * 19 + y * 7) % 120
            pixels[y * width + x] = rgb(value, value, value)
        }
        listOf(8 to 8, 17 to 18, 29 to 10, 40 to 24, 52 to 15, 24 to 27)
            .forEachIndexed { index, (x, y) ->
                val value = 100 + index * 12
                pixels[(y + dy) * width + x + dx] = rgb(value, value, value)
            }
        return ArgbPixelImage(width, height, pixels)
    }

    private fun syntheticDeviceDarkScene(): ArgbPixelImage {
        val width = 96
        val height = 72
        val random = java.util.Random(6355L)
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val gradient = x * 8 / width
            val noise = random.nextInt(5) - 2
            pixels[y * width + x] = rgb(
                8 + gradient + noise,
                6 + gradient / 2 + noise,
                5 + gradient / 3 + noise
            )
        }
        for (x in 0 until width) pixels[50 * width + x] = rgb(35, 31, 28)
        for (y in 54 until height) for (x in 0 until width) {
            val foreground = 22 + (x * 7 + y * 3) % 18
            pixels[y * width + x] = rgb(foreground + 4, foreground + 2, foreground)
        }
        listOf(
            Triple(9, 9, 85), Triple(20, 17, 105), Triple(34, 11, 130),
            Triple(48, 28, 160), Triple(63, 15, 190), Triple(78, 31, 220),
            Triple(86, 8, 145), Triple(27, 38, 115)
        ).forEach { (x, y, value) -> pixels[y * width + x] = rgb(value, value, value + 5) }
        pixels[6 * width + 70] = rgb(255, 255, 255)
        return ArgbPixelImage(width, height, pixels)
    }

    private fun averageChannels(image: ArgbPixelImage): Triple<Int, Int, Int> {
        var red = 0L
        var green = 0L
        var blue = 0L
        image.pixels.forEach {
            red += it ushr 16 and 0xFF
            green += it ushr 8 and 0xFF
            blue += it and 0xFF
        }
        return Triple(
            (red / image.pixels.size).toInt(),
            (green / image.pixels.size).toInt(),
            (blue / image.pixels.size).toInt()
        )
    }

    private fun rgb(red: Int, green: Int, blue: Int) =
        (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
}
