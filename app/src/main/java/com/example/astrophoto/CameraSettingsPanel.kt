package com.example.astrophoto

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.example.astrophoto.ui.AstroPanelHandle
import com.example.astrophoto.ui.AstroSpacing
import com.example.astrophoto.ui.AstroTestTags
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class CameraPanelAnchor {
    COLLAPSED,
    PARTIAL,
    EXPANDED
}

data class CameraPanelAnchors(
    val expandedOffset: Float,
    val partialOffset: Float,
    val collapsedOffset: Float
) {
    fun offset(anchor: CameraPanelAnchor): Float = when (anchor) {
        CameraPanelAnchor.EXPANDED -> expandedOffset
        CameraPanelAnchor.PARTIAL -> partialOffset
        CameraPanelAnchor.COLLAPSED -> collapsedOffset
    }
}

fun calculateCameraPanelAnchors(
    maximumHeightPx: Float,
    collapsedHeightPx: Float
): CameraPanelAnchors {
    val safeMaximum = maximumHeightPx.coerceAtLeast(1f)
    val safeCollapsed = collapsedHeightPx.coerceIn(1f, safeMaximum)
    val partialHeight = maxOf(safeMaximum * 0.58f, safeCollapsed)
    return CameraPanelAnchors(
        expandedOffset = 0f,
        partialOffset = (safeMaximum - partialHeight).coerceAtLeast(0f),
        collapsedOffset = (safeMaximum - safeCollapsed).coerceAtLeast(0f)
    )
}

fun settleCameraPanelAnchor(
    offsetPx: Float,
    velocityPxPerSecond: Float,
    anchors: CameraPanelAnchors,
    flingThresholdPxPerSecond: Float = 900f
): CameraPanelAnchor {
    val ordered = listOf(
        CameraPanelAnchor.EXPANDED,
        CameraPanelAnchor.PARTIAL,
        CameraPanelAnchor.COLLAPSED
    )
    if (abs(velocityPxPerSecond) >= flingThresholdPxPerSecond) {
        if (velocityPxPerSecond > 0f) {
            ordered.firstOrNull { anchors.offset(it) > offsetPx + 1f }?.let { return it }
        } else {
            ordered.lastOrNull { anchors.offset(it) < offsetPx - 1f }?.let { return it }
        }
    }
    return ordered.minBy { abs(anchors.offset(it) - offsetPx) }
}

fun toggledCameraPanelAnchor(anchor: CameraPanelAnchor): CameraPanelAnchor =
    if (anchor == CameraPanelAnchor.COLLAPSED) {
        CameraPanelAnchor.EXPANDED
    } else {
        CameraPanelAnchor.COLLAPSED
    }

fun cameraPanelBackTarget(anchor: CameraPanelAnchor): CameraPanelAnchor? =
    if (anchor == CameraPanelAnchor.COLLAPSED) null else CameraPanelAnchor.COLLAPSED

fun shouldCameraPanelConsumeScroll(
    deltaY: Float,
    listAtTop: Boolean,
    offsetPx: Float,
    anchors: CameraPanelAnchors
): Boolean =
    deltaY < 0f && offsetPx > anchors.expandedOffset ||
        deltaY > 0f && listAtTop && offsetPx < anchors.collapsedOffset

@Composable
fun rememberCameraPanelAnchor(
    initialAnchor: CameraPanelAnchor
): MutableState<CameraPanelAnchor> = rememberSaveable {
    mutableStateOf(initialAnchor)
}

@Composable
fun CameraSettingsPanel(
    anchor: CameraPanelAnchor,
    onAnchorChanged: (CameraPanelAnchor) -> Unit,
    summary: String,
    modifier: Modifier = Modifier,
    collapsedContent: @Composable ColumnScope.() -> Unit,
    expandedContent: @Composable ColumnScope.(androidx.compose.foundation.ScrollState) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val landscape = maxWidth > maxHeight
        val maximumHeight = maxHeight * if (landscape) 0.94f else 0.82f
        val collapsedHeight = if (landscape) 184.dp else 208.dp
        val maximumHeightPx = with(density) { maximumHeight.toPx() }
        val collapsedHeightPx = with(density) {
            collapsedHeight.coerceAtMost(maximumHeight).toPx()
        }
        val anchors = remember(maximumHeightPx, collapsedHeightPx) {
            calculateCameraPanelAnchors(maximumHeightPx, collapsedHeightPx)
        }
        val offsetState = remember(anchors) {
            mutableFloatStateOf(anchors.offset(anchor))
        }
        val draggingState = remember { mutableStateOf(false) }
        val animationJob = remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        val currentOnAnchorChanged = rememberUpdatedState(onAnchorChanged)
        val currentAnchor = rememberUpdatedState(anchor)
        val scrollState = rememberScrollState()

        fun animateTo(target: CameraPanelAnchor) {
            animationJob.value?.cancel()
            currentOnAnchorChanged.value(target)
            animationJob.value = scope.launch {
                animate(
                    initialValue = offsetState.floatValue,
                    targetValue = anchors.offset(target),
                    animationSpec = tween(210)
                ) { value, _ ->
                    offsetState.floatValue = value
                }
            }
        }

        val dragState = rememberDraggableState { delta ->
            animationJob.value?.cancel()
            offsetState.floatValue = (offsetState.floatValue + delta)
                .coerceIn(anchors.expandedOffset, anchors.collapsedOffset)
        }
        val dragModifier = Modifier.draggable(
            state = dragState,
            orientation = Orientation.Vertical,
            onDragStarted = {
                animationJob.value?.cancel()
                draggingState.value = true
            },
            onDragStopped = { velocity ->
                draggingState.value = false
                animateTo(
                    settleCameraPanelAnchor(
                        offsetPx = offsetState.floatValue,
                        velocityPxPerSecond = velocity,
                        anchors = anchors
                    )
                )
            }
        )
        val nestedScrollConnection = remember(anchors, scrollState) {
            CameraPanelNestedScrollConnection(
                anchors = anchors,
                offsetState = offsetState,
                scrollAtTop = { scrollState.value == 0 },
                animationJob = animationJob,
                scope = scope,
                anchorChange = currentOnAnchorChanged
            )
        }

        androidx.compose.runtime.LaunchedEffect(anchor, anchors) {
            if (!draggingState.value) {
                animationJob.value?.cancel()
                animate(
                    initialValue = offsetState.floatValue,
                    targetValue = anchors.offset(anchor),
                    animationSpec = tween(210)
                ) { value, _ -> offsetState.floatValue = value }
            }
        }

        val visibleHeightPx = maximumHeightPx - offsetState.floatValue
        val visibleHeight = with(density) { visibleHeightPx.toDp() }
        val showExpandedContent = currentAnchor.value != CameraPanelAnchor.COLLAPSED ||
            offsetState.floatValue < (anchors.partialOffset + anchors.collapsedOffset) / 2f

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(visibleHeight)
                .testTag(AstroTestTags.CameraSettingsPanel)
                .semantics { stateDescription = currentAnchor.value.name }
                .nestedScroll(nestedScrollConnection),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                CameraPanelHeader(
                    expanded = showExpandedContent,
                    summary = summary,
                    onToggle = {
                        animateTo(toggledCameraPanelAnchor(currentAnchor.value))
                    },
                    modifier = dragModifier
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (showExpandedContent) {
                        expandedContent(scrollState)
                    } else {
                        collapsedContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPanelHeader(
    expanded: Boolean,
    summary: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .padding(horizontal = AstroSpacing.Md, vertical = AstroSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 48.dp)
                .testTag(AstroTestTags.CameraSettingsHandle)
                .clickable(role = Role.Button, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            AstroPanelHandle()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (expanded) "Настройки камеры" else "Параметры съёмки",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Text(
            text = if (expanded) "Свернуть" else "Развернуть",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private class CameraPanelNestedScrollConnection(
    private val anchors: CameraPanelAnchors,
    private val offsetState: androidx.compose.runtime.MutableFloatState,
    private val scrollAtTop: () -> Boolean,
    private val animationJob: androidx.compose.runtime.MutableState<Job?>,
    private val scope: CoroutineScope,
    private val anchorChange: State<(CameraPanelAnchor) -> Unit>
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        val shouldMovePanel = shouldCameraPanelConsumeScroll(
            deltaY = delta,
            listAtTop = scrollAtTop(),
            offsetPx = offsetState.floatValue,
            anchors = anchors
        )
        if (!shouldMovePanel) return Offset.Zero
        animationJob.value?.cancel()
        val previous = offsetState.floatValue
        offsetState.floatValue = (previous + delta)
            .coerceIn(anchors.expandedOffset, anchors.collapsedOffset)
        return Offset(0f, offsetState.floatValue - previous)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        if (available.y <= 0f || !scrollAtTop()) return Offset.Zero
        return onPreScroll(available, source)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val canCollapse = available.y > 0f && scrollAtTop() &&
            offsetState.floatValue < anchors.collapsedOffset
        val canExpand = available.y < 0f && offsetState.floatValue > anchors.expandedOffset
        if (!canCollapse && !canExpand) return Velocity.Zero
        val target = settleCameraPanelAnchor(
            offsetPx = offsetState.floatValue,
            velocityPxPerSecond = available.y,
            anchors = anchors
        )
        animationJob.value?.cancel()
        anchorChange.value(target)
        animationJob.value = scope.launch {
            animate(
                initialValue = offsetState.floatValue,
                targetValue = anchors.offset(target),
                animationSpec = tween(210)
            ) { value, _ -> offsetState.floatValue = value }
        }
        return Velocity(0f, available.y)
    }
}
