package com.joe6355.astrophoto

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.joe6355.astrophoto.ui.AstroPanelHandle
import com.joe6355.astrophoto.ui.AstroSpacing
import com.joe6355.astrophoto.ui.AstroTestTags
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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
    var consumedInsets by remember { mutableStateOf(WindowInsets(0, 0, 0, 0)) }
    var compactContentHeightPx by remember { mutableIntStateOf(0) }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()
        .onConsumedWindowInsetsChanged { consumedInsets = it }) {
        val density = LocalDensity.current
        val landscape = maxWidth > maxHeight
        val maximumHeight = maxHeight * if (landscape) 0.94f else 0.82f
        val bottomInsetPx = WindowInsets.navigationBars.exclude(consumedInsets).getBottom(density)
        // The running series adds status lines and progress; size the compact panel to fit them.
        val contentHeight = with(density) { (compactContentHeightPx + headerHeightPx + bottomInsetPx).toDp() }
        val collapsedHeight = maxOf(180.dp * density.fontScale.coerceIn(1f, 1.6f), contentHeight)
        val maximumHeightPx = with(density) { maximumHeight.toPx() }
        val collapsedHeightPx = with(density) {
            collapsedHeight.coerceAtMost(maximumHeight).toPx()
        }
        val anchors = remember(maximumHeightPx, collapsedHeightPx) {
            calculateCameraPanelAnchors(maximumHeightPx, collapsedHeightPx)
        }
        val draggingState = remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val motion = remember(anchors, scope) { CameraPanelMotion(anchors, anchor, scope) }
        DisposableEffect(motion) { onDispose { motion.cancelAnimation() } }
        val currentOnAnchorChanged = rememberUpdatedState(onAnchorChanged)
        val currentAnchor = rememberUpdatedState(anchor)
        val scrollState = rememberScrollState()

        fun animateTo(target: CameraPanelAnchor) {
            currentOnAnchorChanged.value(target)
            motion.animateTo(target)
        }

        val dragState = rememberDraggableState { delta -> motion.dragBy(delta) }
        val dragModifier = Modifier.draggable(
            state = dragState,
            orientation = Orientation.Vertical,
            onDragStarted = {
                motion.cancelAnimation()
                draggingState.value = true
            },
            onDragStopped = { velocity ->
                draggingState.value = false
                animateTo(
                    settleCameraPanelAnchor(
                        offsetPx = motion.offset.floatValue,
                        velocityPxPerSecond = velocity,
                        anchors = anchors
                    )
                )
            }
        )
        val nestedScrollConnection = remember(motion, scrollState) {
            CameraPanelNestedScrollConnection(
                motion = motion,
                scrollAtTop = { scrollState.value == 0 },
                anchorChange = currentOnAnchorChanged
            )
        }

        androidx.compose.runtime.LaunchedEffect(anchor, motion) {
            if (!draggingState.value) {
                motion.animateTo(anchor)
            }
        }

        val showExpandedContent by remember(motion) {
            derivedStateOf {
                currentAnchor.value != CameraPanelAnchor.COLLAPSED ||
                    motion.offset.floatValue < (anchors.partialOffset + anchors.collapsedOffset) / 2f
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Read the drag offset in placement: settings retain constant constraints.
                .offset { IntOffset(0, motion.offset.floatValue.roundToInt()) }
                .height(maximumHeight)
                .testTag(AstroTestTags.CameraSettingsPanel)
                .semantics { stateDescription = currentAnchor.value.name }
                .nestedScroll(nestedScrollConnection),
            color = MaterialTheme.colorScheme.surface,
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
                    modifier = dragModifier.onSizeChanged { headerHeightPx = it.height }
                )
                Layout(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    content = {
                        // The opaque compact Surface also blocks touches to the settings behind it.
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                            Box(Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .onSizeChanged { compactContentHeightPx = it.height }) {
                                    collapsedContent()
                                }
                            }
                        }
                        Column(Modifier.fillMaxSize().then(
                            if (showExpandedContent) Modifier else Modifier.clearAndSetSemantics {}
                        )) {
                            expandedContent(scrollState)
                        }
                    }
                ) { measurables, constraints ->
                    val compact = measurables[0].measure(constraints)
                    val expanded = measurables[1].measure(constraints)
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        // Keep settings placed and their drawing prepared behind the compact body.
                        // First placement in the middle of a drag otherwise causes a visible hitch.
                        expanded.placeRelative(0, 0)
                        if (!showExpandedContent) compact.placeRelative(0, 0)
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
            .heightIn(min = 56.dp)
            .testTag(AstroTestTags.CameraSettingsHandle)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = AstroSpacing.Md, vertical = AstroSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            AstroPanelHandle()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (expanded) "Все настройки" else "Настройки камеры ↑",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** One cancellable animation owner for handle drags, nested scroll, taps and Back. */
private class CameraPanelMotion(
    val anchors: CameraPanelAnchors,
    initialAnchor: CameraPanelAnchor,
    private val scope: CoroutineScope
) {
    val offset = mutableFloatStateOf(anchors.offset(initialAnchor))
    private var animationJob: Job? = null
    private var animationTarget: CameraPanelAnchor? = initialAnchor

    fun cancelAnimation() {
        animationJob?.cancel()
        animationTarget = null
    }

    fun dragBy(delta: Float): Float {
        cancelAnimation()
        val previous = offset.floatValue
        offset.floatValue = (previous + delta)
            .coerceIn(anchors.expandedOffset, anchors.collapsedOffset)
        return offset.floatValue - previous
    }

    fun animateTo(target: CameraPanelAnchor) {
        if (animationTarget == target &&
            (animationJob?.isActive == true || offset.floatValue == anchors.offset(target))) return
        cancelAnimation()
        animationTarget = target
        animationJob = scope.launch {
            animate(offset.floatValue, anchors.offset(target), animationSpec = tween(210)) { value, _ ->
                offset.floatValue = value
            }
        }
    }
}

private class CameraPanelNestedScrollConnection(
    private val motion: CameraPanelMotion,
    private val scrollAtTop: () -> Boolean,
    private val anchorChange: State<(CameraPanelAnchor) -> Unit>
) : NestedScrollConnection {
    private val anchors get() = motion.anchors
    private var panelMovedDuringScroll = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        val shouldMovePanel = shouldCameraPanelConsumeScroll(
            deltaY = delta,
            listAtTop = scrollAtTop(),
            offsetPx = motion.offset.floatValue,
            anchors = anchors
        )
        if (!shouldMovePanel) return Offset.Zero
        val consumed = motion.dragBy(delta)
        if (consumed != 0f) panelMovedDuringScroll = true
        return Offset(0f, consumed)
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
            motion.offset.floatValue < anchors.collapsedOffset
        val canExpand = available.y < 0f && motion.offset.floatValue > anchors.expandedOffset
        // A drag may already reach an endpoint or end with zero velocity. Commit its
        // anchor too; otherwise the visible height and the saved/back-button state disagree.
        if (!panelMovedDuringScroll && !canCollapse && !canExpand) return Velocity.Zero
        panelMovedDuringScroll = false
        val target = settleCameraPanelAnchor(
            offsetPx = motion.offset.floatValue,
            velocityPxPerSecond = available.y,
            anchors = anchors
        )
        anchorChange.value(target)
        motion.animateTo(target)
        return Velocity(0f, available.y)
    }
}
