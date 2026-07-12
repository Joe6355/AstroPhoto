package com.example.astrophoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPanelStateTest {
    private val anchors = calculateCameraPanelAnchors(
        maximumHeightPx = 1_000f,
        collapsedHeightPx = 220f
    )

    @Test
    fun anchorsAreOrderedAndAdaptToHeight() {
        assertEquals(0f, anchors.expandedOffset, 0f)
        assertTrue(anchors.partialOffset in 1f..<anchors.collapsedOffset)
        assertEquals(780f, anchors.collapsedOffset, 0f)

        val shortScreen = calculateCameraPanelAnchors(400f, 220f)
        assertTrue(shortScreen.partialOffset < shortScreen.collapsedOffset)
        assertTrue(shortScreen.collapsedOffset < anchors.collapsedOffset)
    }

    @Test
    fun slowDragSettlesToNearestAnchor() {
        assertEquals(
            CameraPanelAnchor.PARTIAL,
            settleCameraPanelAnchor(anchors.partialOffset + 20f, 0f, anchors)
        )
        assertEquals(
            CameraPanelAnchor.COLLAPSED,
            settleCameraPanelAnchor(anchors.collapsedOffset - 20f, 0f, anchors)
        )
    }

    @Test
    fun flingUsesNextAnchorInVelocityDirection() {
        assertEquals(
            CameraPanelAnchor.PARTIAL,
            settleCameraPanelAnchor(anchors.expandedOffset, 1_200f, anchors)
        )
        assertEquals(
            CameraPanelAnchor.EXPANDED,
            settleCameraPanelAnchor(anchors.partialOffset, -1_200f, anchors)
        )
    }

    @Test
    fun handleTapTogglesOnlyCollapsedAndExpanded() {
        assertEquals(
            CameraPanelAnchor.EXPANDED,
            toggledCameraPanelAnchor(CameraPanelAnchor.COLLAPSED)
        )
        assertEquals(
            CameraPanelAnchor.COLLAPSED,
            toggledCameraPanelAnchor(CameraPanelAnchor.PARTIAL)
        )
        assertEquals(
            CameraPanelAnchor.COLLAPSED,
            toggledCameraPanelAnchor(CameraPanelAnchor.EXPANDED)
        )
    }

    @Test
    fun listScrollOnlyCollapsesAtTop() {
        assertFalse(
            shouldCameraPanelConsumeScroll(
                deltaY = 40f,
                listAtTop = false,
                offsetPx = anchors.partialOffset,
                anchors = anchors
            )
        )
        assertTrue(
            shouldCameraPanelConsumeScroll(
                deltaY = 40f,
                listAtTop = true,
                offsetPx = anchors.partialOffset,
                anchors = anchors
            )
        )
        assertTrue(
            shouldCameraPanelConsumeScroll(
                deltaY = -40f,
                listAtTop = false,
                offsetPx = anchors.partialOffset,
                anchors = anchors
            )
        )
    }

    @Test
    fun backCollapsesPanelBeforeNavigation() {
        assertEquals(
            CameraPanelAnchor.COLLAPSED,
            cameraPanelBackTarget(CameraPanelAnchor.EXPANDED)
        )
        assertEquals(
            CameraPanelAnchor.COLLAPSED,
            cameraPanelBackTarget(CameraPanelAnchor.PARTIAL)
        )
        assertNull(cameraPanelBackTarget(CameraPanelAnchor.COLLAPSED))
    }

    @Test
    fun activeCaptureRejectsRepeatedTap() {
        assertFalse(
            canStartSingleCapture(
                isCapturing = true,
                seriesRunning = false,
                darkFramesRunning = false,
                testShotRunning = false,
                permissionRequestPending = false
            )
        )
        assertFalse(
            canStartSingleCapture(
                isCapturing = false,
                seriesRunning = false,
                darkFramesRunning = false,
                testShotRunning = false,
                permissionRequestPending = true
            )
        )
        assertTrue(
            canStartSingleCapture(
                isCapturing = false,
                seriesRunning = false,
                darkFramesRunning = false,
                testShotRunning = false,
                permissionRequestPending = false
            )
        )
    }
}
