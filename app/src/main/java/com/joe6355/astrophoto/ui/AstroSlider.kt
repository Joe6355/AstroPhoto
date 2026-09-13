package com.joe6355.astrophoto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Keeps Material slider gestures and accessibility, with a thin track and round thumb. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AstroSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    val colors = MaterialTheme.colorScheme
    val active = colors.primary.copy(alpha = if (enabled) 1f else 0.38f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Slider(value = value, onValueChange = onValueChange, modifier = modifier,
        enabled = enabled, valueRange = valueRange,
        thumb = {
            Box(Modifier.size(20.dp).background(active, CircleShape).border(2.dp, colors.surface, CircleShape))
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val fraction = ((state.value - valueRange.start) /
                    (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                val start = Offset(if (rtl) size.width else 0f, size.height / 2)
                val end = Offset(if (rtl) 0f else size.width, size.height / 2)
                drawLine(colors.outlineVariant, start, end, size.height, StrokeCap.Round)
                drawLine(active, start, Offset(size.width * if (rtl) 1f - fraction else fraction, size.height / 2),
                    size.height, StrokeCap.Round)
            }
        })
}
