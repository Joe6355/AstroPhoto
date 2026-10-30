package com.joe6355.astrophoto

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimelapsePublicationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun successFinalizesAndClosesBeforePublication() {
        val events = mutableListOf<String>()
        val size = publishSessionTimelapse(
            write = {
                completeTimelapseEncoding(
                    encode = { events += "samples" },
                    stopMuxer = { events += "finalize" },
                    releaseResources = { events += "release" }
                )
                123L
            },
            closeOutput = { events += "close" },
            publish = { events += "publish"; 1 },
            discard = { events += "discard" }
        )
        assertEquals(123L, size)
        assertEquals(listOf("samples", "finalize", "release", "close", "publish"), events)
    }

    @Test fun failedFinalizationDiscardsOnlyNewOutputAndRetrySucceeds() {
        val directory = temporary.newFolder()
        val source = File(directory, "source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val oldVideo = File(directory, "old.mp4").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val output = File(directory, "new.mp4")
        val failure = IllegalStateException("Failed to stop the muxer")
        var published = 0
        var released = 0
        var closed = 0

        fun export(failFinalization: Boolean) = publishSessionTimelapse(
            write = {
                completeTimelapseEncoding(
                    encode = { output.writeText("sample payload") },
                    stopMuxer = {
                        if (failFinalization) throw failure
                        output.appendText("; finalized")
                    },
                    releaseResources = { released++ }
                )
                output.length()
            },
            closeOutput = { closed++ },
            publish = { published++; 1 },
            discard = { check(output.delete()) }
        )

        val error = assertThrows(IllegalStateException::class.java) { export(true) }
        assertSame(failure, error)
        assertFalse(output.exists())
        assertEquals(0, published)
        assertEquals(1, released)
        assertEquals(1, closed)
        assertEquals(25L, export(false))
        assertEquals("sample payload; finalized", output.readText())
        assertEquals(1, published)
        assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), oldVideo.readBytes())
    }

    @Test fun noSpaceDuringWriteFinalizationOrCloseNeverPublishes() {
        for (phase in listOf("write", "finalize", "close")) {
            val failure = IOException("write failed: ENOSPC (No space left on device)")
            var discarded = 0
            var closed = 0
            val error = assertThrows(IOException::class.java) {
                publishSessionTimelapse(
                    write = {
                        completeTimelapseEncoding(
                            encode = { if (phase == "write") throw failure },
                            stopMuxer = { if (phase == "finalize") throw failure },
                            releaseResources = {}
                        )
                        77L
                    },
                    closeOutput = { closed++; if (phase == "close") throw failure },
                    publish = { fail("Must not publish after $phase failure"); 1 },
                    discard = { discarded++ }
                )
            }
            assertEquals(phase, TIMELAPSE_STORAGE_FULL_MESSAGE, error.message)
            assertSame(phase, failure, error.cause)
            assertEquals(phase, 1, discarded)
            assertEquals(phase, 1, closed)
        }
    }

    @Test fun cancellationDuringEncodingOrBeforeCommitKeepsIdentityAndCleansOutput() {
        for (late in listOf(false, true)) {
            val cancellation = CancellationException("cancel")
            var discarded = 0
            var closed = 0
            var stopCalled = false
            val error = assertThrows(CancellationException::class.java) {
                publishSessionTimelapse(
                    write = {
                        completeTimelapseEncoding(
                            encode = { if (!late) throw cancellation },
                            stopMuxer = { stopCalled = true },
                            releaseResources = {}
                        )
                        77L
                    },
                    closeOutput = { closed++ },
                    publish = { throw cancellation },
                    discard = { discarded++ }
                )
            }
            assertSame(cancellation, error)
            assertEquals(late, stopCalled)
            assertEquals(1, discarded)
            assertEquals(1, closed)
        }
    }

    @Test fun publicationMustConfirmExactlyOneRow() {
        for (rows in listOf(0, 2)) {
            var discarded = 0
            var closed = false
            assertThrows(IllegalStateException::class.java) {
                publishSessionTimelapse(
                    write = { 1L },
                    closeOutput = { closed = true },
                    publish = { assertTrue(closed); rows },
                    discard = { discarded++ }
                )
            }
            assertEquals(1, discarded)
        }
    }

    @Test fun cleanupAttemptsEveryResourceAndKeepsOriginalFailure() {
        val primary = IOException("JPEG read failed")
        val release = IOException("renderer release failed")
        val codec = IOException("codec release failed")
        val close = IOException("descriptor close failed")
        val discard = IOException("provider unavailable")
        val events = mutableListOf<String>()
        val error = assertThrows(IOException::class.java) {
            publishSessionTimelapse(
                write = {
                    completeTimelapseEncoding(
                        encode = { throw primary },
                        stopMuxer = { fail("Do not finalize incomplete samples") },
                        releaseResources = {
                            releaseTimelapseResources(
                                { events += "renderer"; throw release },
                                { events += "codec"; throw codec },
                                { events += "muxer" }
                            )
                        }
                    )
                    1L
                },
                closeOutput = { events += "close"; throw close },
                publish = { fail("Must not publish"); 1 },
                discard = { events += "discard"; throw discard }
            )
        }
        assertSame(primary, error)
        assertEquals(listOf("renderer", "codec", "muxer", "close", "discard"), events)
        assertArrayEquals(arrayOf(release, close, discard), error.suppressed)
        assertArrayEquals(arrayOf(codec), release.suppressed)
    }

    @Test fun resourceReleaseFailurePreventsPublicationAndStillClosesOutput() {
        val failure = IOException("muxer release failed")
        var closed = false
        var discarded = false
        val error = assertThrows(IOException::class.java) {
            publishSessionTimelapse(
                write = {
                    completeTimelapseEncoding({}, {}, { throw failure })
                    1L
                },
                closeOutput = { closed = true },
                publish = { fail("Must not publish"); 1 },
                discard = { discarded = true }
            )
        }
        assertSame(failure, error)
        assertTrue(closed)
        assertTrue(discarded)
    }

    @Test fun emptyOutputIsDiscardedWithoutPublication() {
        var closed = false
        var discarded = false
        assertThrows(IllegalStateException::class.java) {
            publishSessionTimelapse(
                write = { 0L },
                closeOutput = { closed = true },
                publish = { fail("Empty output must not be published"); 1 },
                discard = { discarded = true }
            )
        }
        assertTrue(closed)
        assertTrue(discarded)
    }

    @Test fun legacyReservationKeepsExistingVideoAndUsesAvailableSuffix() {
        val directory = temporary.newFolder()
        val requested = File(directory, "video.mp4").apply { writeText("old video") }
        val sibling = File(directory, "video_2.mp4").apply { writeText("other video") }
        val reserved = reserveTimelapseFile(requested)
        assertEquals("video_3.mp4", reserved.name)
        assertTrue(reserved.isFile)
        assertEquals(0L, reserved.length())
        assertEquals("old video", requested.readText())
        assertEquals("other video", sibling.readText())
    }

    @Test fun concurrentLegacyReservationsCannotOwnTheSameFile() {
        val requested = File(temporary.newFolder(), "video.mp4")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                executor.submit(Callable {
                    check(start.await(5, TimeUnit.SECONDS))
                    reserveTimelapseFile(requested)
                })
            }
            start.countDown()
            val reserved = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(setOf("video.mp4", "video_2.mp4"), reserved.map { it.name }.toSet())
            reserved.forEach { assertTrue(it.isFile) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun constructorFailureCleansReservationAndAllowsRetryWithoutTouchingOldVideo() {
        val requested = File(temporary.newFolder(), "video.mp4").apply { writeText("old video") }
        val reserved = reserveTimelapseFile(requested)
        val failure = IOException("muxer constructor failed")
        var descriptorClosed = false
        val error = assertThrows(IOException::class.java) {
            withOwnedOutputCleanup({
                releaseTimelapseResources(
                    { descriptorClosed = true },
                    { check(reserved.delete()) }
                )
            }) {
                reserved.writeText("partial output")
                throw failure
            }
        }
        assertSame(failure, error)
        assertTrue(descriptorClosed)
        assertFalse(reserved.exists())
        assertEquals("old video", requested.readText())
        assertEquals(reserved, reserveTimelapseFile(requested))
    }
}
