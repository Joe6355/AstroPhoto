package com.joe6355.astrophoto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import com.joe6355.astrophoto.ui.theme.AstroColors

/** Decorative artwork, never a substitute for a session thumbnail or live preview. */
@Composable
fun OrbitArtwork(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val light = colors.background.luminance() > 0.5f
    val background = if (light) AstroColors.Background else colors.background
    val accent = if (light) AstroColors.Secondary else colors.secondary
    val starlight = if (light) AstroColors.TextPrimary else colors.onBackground
    val stars = remember {
        val random = Random(6355)
        List(480) { Triple(random.nextFloat(), random.nextFloat(), random.nextFloat()) }
    }
    Canvas(modifier) {
        drawRect(Brush.linearGradient(listOf(background, lerp(background, accent, 0.24f), background)))
        // A diffuse diagonal band gives the sky depth without a bitmap or animation.
        repeat(14) { index ->
            val center = Offset(size.width * (0.26f + index * 0.045f), size.height * (1f - index * 0.072f))
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.045f), Color.Transparent),
                center, size.width * 0.27f), size.width * 0.27f, center)
        }
        stars.forEachIndexed { index, (x, y, brightness) ->
            val center = Offset(size.width * x, size.height * y)
            if (index % 91 == 0) {
                drawCircle(Brush.radialGradient(listOf(starlight.copy(alpha = 0.6f), Color.Transparent),
                    center, 4.dp.toPx()), 4.dp.toPx(), center)
            }
            drawCircle(starlight.copy(alpha = 0.22f + brightness * 0.68f),
                (0.2f + brightness * 0.5f).dp.toPx(), center)
        }
        val radius = size.minDimension * 0.36f
        val center = Offset(size.width * 0.86f, size.height * 0.38f)
        drawCircle(accent.copy(alpha = 0.3f), radius, center, style = Stroke(0.65.dp.toPx()))
        drawCircle(accent.copy(alpha = 0.24f), radius * 0.67f, center, style = Stroke(0.65.dp.toPx()))
        drawRect(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background.copy(alpha = 0.72f))))
        val horizon = Path().apply {
            moveTo(0f, size.height)
            repeat(46) { index ->
                val x = index / 45f
                lineTo(size.width * x, size.height * (0.975f - 0.012f * kotlin.math.sin(x * 17f) - stars[index].third * 0.01f))
            }
            lineTo(size.width, size.height); close()
        }
        drawPath(horizon, background)
    }
}

enum class OrbitDestination(val label: String) {
    HOME("Главная"), CAMERA("Камера"), SESSIONS("Съёмки")
}

@Composable
fun OrbitNavigationBar(selected: OrbitDestination, onSelected: (OrbitDestination) -> Unit, enabled: Boolean = true) {
    Column {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    NavigationBar(containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
        OrbitDestination.entries.forEach { destination ->
            NavigationBarItem(selected = destination == selected, onClick = { onSelected(destination) },
                enabled = enabled, icon = { OrbitNavigationIcon(destination) },
                label = { Text(destination.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
    }
}

@Composable
private fun OrbitNavigationIcon(destination: OrbitDestination) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val stroke = Stroke(1.5.dp.toPx())
        when (destination) {
            OrbitDestination.HOME -> {
                val path = Path().apply {
                    moveTo(w * .12f, w * .43f); lineTo(w * .5f, w * .1f)
                    lineTo(w * .88f, w * .43f); lineTo(w * .88f, w * .9f)
                    lineTo(w * .6f, w * .9f); lineTo(w * .6f, w * .6f)
                    lineTo(w * .4f, w * .6f); lineTo(w * .4f, w * .9f)
                    lineTo(w * .12f, w * .9f); close()
                }
                drawPath(path, color, style = stroke)
            }
            OrbitDestination.CAMERA -> {
                drawRoundRect(color, Offset(w * .08f, w * .25f), Size(w * .84f, w * .6f), style = stroke)
                drawCircle(color, w * .17f, Offset(w * .5f, w * .55f), style = stroke)
                drawLine(color, Offset(w * .35f, w * .13f), Offset(w * .65f, w * .13f), stroke.width)
            }
            OrbitDestination.SESSIONS -> {
                drawRoundRect(color, Offset(w * .23f, w * .08f), Size(w * .67f, w * .67f), style = stroke)
                drawCircle(color, w * .055f, Offset(w * .67f, w * .28f))
                drawLine(color, Offset(w * .1f, w * .25f), Offset(w * .1f, w * .9f), stroke.width)
                drawLine(color, Offset(w * .1f, w * .9f), Offset(w * .75f, w * .9f), stroke.width)
            }
        }
    }
}
