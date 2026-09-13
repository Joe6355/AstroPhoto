package com.joe6355.astrophoto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableFloatStateOf
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
enum class OrbitScene(val label: String) {
    ORBITS("Орбиты"), MILKY_WAY("Млечный путь"), METEORS("Метеоры"),
    CONSTELLATIONS("Созвездия"), ECLIPSE("Затмение")
}

@Composable
fun OrbitArtwork(modifier: Modifier = Modifier, scene: OrbitScene = OrbitScene.METEORS, animated: Boolean = false) {
    val phase = if (animated) {
        rememberInfiniteTransition(label = "sky").animateFloat(0f, 1f,
            animationSpec = infiniteRepeatable(tween(40_000, easing = LinearEasing)), label = "starlight")
    } else remember { mutableFloatStateOf(0f) }
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
        val time = phase.value
        drawRect(Brush.linearGradient(listOf(background, lerp(background, accent, 0.16f), background)))
        // Keep atmospheric light behind crisp, pixel-scale star cores.
        repeat(14) { index ->
            val center = Offset(size.width * (0.26f + index * 0.045f), size.height * (1f - index * 0.072f))
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.025f), Color.Transparent),
                center, size.width * if (scene == OrbitScene.MILKY_WAY) 0.38f else 0.27f),
                size.width * if (scene == OrbitScene.MILKY_WAY) 0.38f else 0.27f, center)
        }
        stars.forEachIndexed { index, (x, y, brightness) ->
            val drift = kotlin.math.sin(time * 2f * kotlin.math.PI.toFloat()) * 0.007f
            val center = Offset(size.width * (x + drift * brightness), size.height * y)
            val twinkle = 0.9f + 0.1f * kotlin.math.sin(time * 8f * kotlin.math.PI.toFloat() + index)
            if (index % 91 == 0) {
                drawCircle(Brush.radialGradient(listOf(starlight.copy(alpha = 0.35f), Color.Transparent),
                    center, 2.5.dp.toPx()), 2.5.dp.toPx(), center)
            }
            drawCircle(starlight.copy(alpha = (0.38f + brightness * 0.62f) * twinkle),
                (0.35f + brightness * 0.55f).dp.toPx(), center)
        }
        val radius = size.minDimension * 0.36f
        val center = Offset(size.width * 0.86f, size.height * 0.38f)
        if (scene == OrbitScene.ORBITS) {
            val pulse = 1f + kotlin.math.sin(time * 2f * kotlin.math.PI.toFloat()) * 0.035f
            drawCircle(accent.copy(alpha = 0.3f), radius * pulse, center, style = Stroke(0.65.dp.toPx()))
            drawCircle(accent.copy(alpha = 0.24f), radius * 0.67f * pulse, center, style = Stroke(0.65.dp.toPx()))
        }
        if (scene == OrbitScene.MILKY_WAY) {
            stars.take(240).forEach { (x, y, brightness) ->
                val bandY = 0.93f - x * 0.9f + (y - 0.5f) * 0.22f
                drawCircle(starlight.copy(alpha = 0.24f + brightness * 0.4f), 0.5.dp.toPx(),
                    Offset(size.width * x, size.height * bandY))
            }
        }
        if (scene == OrbitScene.METEORS) {
            repeat(3) { index ->
                val progress = (time * 6f + index / 3f) % 1f
                if (progress < 0.38f) {
                    val travel = progress / 0.38f
                    val head = Offset(size.width * (0.2f + travel * 0.7f), size.height * (0.08f + index * 0.11f + travel * 0.26f))
                    val tail = head - Offset(size.width * 0.17f, size.height * 0.084f)
                    val intensity = kotlin.math.sin(travel * kotlin.math.PI.toFloat())
                    drawLine(Brush.linearGradient(listOf(Color.Transparent, starlight.copy(alpha = intensity)), tail, head),
                        tail, head, 1.2.dp.toPx())
                    drawCircle(starlight.copy(alpha = intensity), 1.5.dp.toPx(), head)
                }
            }
        }
        if (scene == OrbitScene.CONSTELLATIONS) {
            // A decorative asterism, not a labelled astronomical sky map.
            val points = listOf(.17f to .21f, .32f to .16f, .48f to .26f,
                .64f to .23f, .81f to .31f, .71f to .45f, .53f to .4f)
                .map { (x, y) -> Offset(size.width * x, size.height * y) }
            val pulse = .72f + .16f * kotlin.math.sin(time * 4f * kotlin.math.PI.toFloat())
            points.zipWithNext().forEach { (start, end) ->
                drawLine(accent.copy(alpha = pulse * .6f), start, end, .8.dp.toPx())
            }
            drawLine(accent.copy(alpha = pulse * .6f), points.last(), points[2], .8.dp.toPx())
            points.forEach { point ->
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .45f), Color.Transparent),
                    point, 6.dp.toPx()), 6.dp.toPx(), point)
                drawCircle(starlight.copy(alpha = pulse), 1.8.dp.toPx(), point)
            }
        }
        if (scene == OrbitScene.ECLIPSE) {
            val eclipseCenter = Offset(size.width * .65f, size.height * .3f)
            val eclipseRadius = size.minDimension * .19f
            val pulse = 1f + .035f * kotlin.math.sin(time * 2f * kotlin.math.PI.toFloat())
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, accent.copy(alpha = .28f),
                Color.Transparent), eclipseCenter, eclipseRadius * 1.65f * pulse),
                eclipseRadius * 1.65f * pulse, eclipseCenter)
            drawCircle(background, eclipseRadius, eclipseCenter)
            drawCircle(accent.copy(alpha = .9f), eclipseRadius, eclipseCenter, style = Stroke(1.dp.toPx()))
            drawArc(starlight.copy(alpha = .92f), 205f + time * 360f, 70f, false,
                eclipseCenter - Offset(eclipseRadius, eclipseRadius),
                Size(eclipseRadius * 2, eclipseRadius * 2), style = Stroke(1.8.dp.toPx()))
        }
        drawRect(Brush.verticalGradient(0f to Color.Transparent, .45f to Color.Transparent,
            1f to background.copy(alpha = 0.88f)))
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
