package com.joe6355.astrophoto.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val NightObservatoryColorScheme = darkColorScheme(
    primary = AstroColors.Primary,
    onPrimary = AstroColors.OnPrimary,
    primaryContainer = AstroColors.PrimaryContainer,
    onPrimaryContainer = AstroColors.OnPrimaryContainer,
    secondary = AstroColors.Secondary,
    onSecondary = AstroColors.OnSecondary,
    secondaryContainer = AstroColors.SecondaryContainer,
    onSecondaryContainer = AstroColors.OnSecondaryContainer,
    tertiary = AstroColors.Success,
    onTertiary = AstroColors.OnSuccess,
    error = AstroColors.Error,
    onError = AstroColors.OnError,
    background = AstroColors.Background,
    onBackground = AstroColors.TextPrimary,
    surface = AstroColors.Surface,
    surfaceDim = AstroColors.Background,
    surfaceBright = AstroColors.SurfaceBright,
    surfaceContainerLowest = AstroColors.Background,
    surfaceContainerLow = AstroColors.Surface,
    surfaceContainer = AstroColors.Surface,
    surfaceContainerHigh = AstroColors.SurfaceElevated,
    surfaceContainerHighest = AstroColors.SurfaceBright,
    surfaceTint = AstroColors.Primary,
    onSurface = AstroColors.TextPrimary,
    surfaceVariant = AstroColors.SurfaceElevated,
    onSurfaceVariant = AstroColors.TextSecondary,
    outline = AstroColors.Outline,
    outlineVariant = AstroColors.OutlineSubtle,
    scrim = AstroColors.Scrim
)

private val DayObservatoryColorScheme = lightColorScheme(
    primary = AstroColors.LightPrimary,
    onPrimary = AstroColors.LightOnPrimary,
    primaryContainer = AstroColors.LightPrimaryContainer,
    onPrimaryContainer = AstroColors.LightOnPrimaryContainer,
    secondary = AstroColors.LightSecondary,
    onSecondary = AstroColors.LightOnSecondary,
    secondaryContainer = AstroColors.LightSecondaryContainer,
    onSecondaryContainer = AstroColors.LightOnSecondaryContainer,
    tertiary = AstroColors.LightSuccess,
    onTertiary = AstroColors.LightOnSuccess,
    error = AstroColors.LightError,
    onError = AstroColors.LightOnError,
    background = AstroColors.LightBackground,
    onBackground = AstroColors.LightTextPrimary,
    surface = AstroColors.LightSurface,
    surfaceDim = AstroColors.LightBackground,
    surfaceBright = AstroColors.LightSurface,
    surfaceContainerLowest = AstroColors.LightSurface,
    surfaceContainerLow = AstroColors.LightBackground,
    surfaceContainer = AstroColors.LightSurface,
    surfaceContainerHigh = AstroColors.LightSurfaceElevated,
    surfaceContainerHighest = AstroColors.LightOutlineSubtle,
    surfaceTint = AstroColors.LightPrimary,
    onSurface = AstroColors.LightTextPrimary,
    surfaceVariant = AstroColors.LightSurfaceElevated,
    onSurfaceVariant = AstroColors.LightTextSecondary,
    outline = AstroColors.LightOutline,
    outlineVariant = AstroColors.LightOutlineSubtle,
    scrim = AstroColors.LightScrim
)

private val VeryDarkObservatoryColorScheme = NightObservatoryColorScheme.copy(
    background = AstroColors.BackgroundVeryDark,
    surface = AstroColors.SurfaceVeryDark,
    surfaceVariant = AstroColors.Surface,
    surfaceDim = AstroColors.BackgroundVeryDark,
    surfaceContainerLowest = AstroColors.BackgroundVeryDark,
    surfaceContainerLow = AstroColors.SurfaceVeryDark,
    surfaceContainer = AstroColors.SurfaceVeryDark,
    surfaceContainerHigh = AstroColors.Surface,
    surfaceContainerHighest = AstroColors.SurfaceElevated
)

private val RedNightObservatoryColorScheme = darkColorScheme(
    primary = AstroColors.RedNightPrimary,
    onPrimary = AstroColors.RedNightOnPrimary,
    primaryContainer = AstroColors.RedNightPrimaryContainer,
    onPrimaryContainer = AstroColors.RedNightOnPrimaryContainer,
    secondary = AstroColors.RedNightSecondary,
    onSecondary = AstroColors.RedNightOnSecondary,
    secondaryContainer = AstroColors.RedNightSecondaryContainer,
    onSecondaryContainer = AstroColors.RedNightOnSecondaryContainer,
    tertiary = AstroColors.RedNightPrimary,
    onTertiary = AstroColors.RedNightOnPrimary,
    error = AstroColors.Error,
    onError = AstroColors.OnError,
    background = AstroColors.RedNightBackground,
    onBackground = AstroColors.RedNightTextPrimary,
    surface = AstroColors.RedNightSurface,
    surfaceDim = AstroColors.RedNightBackground,
    surfaceBright = AstroColors.RedNightSurfaceElevated,
    surfaceContainerLowest = AstroColors.RedNightBackground,
    surfaceContainerLow = AstroColors.RedNightSurface,
    surfaceContainer = AstroColors.RedNightSurface,
    surfaceContainerHigh = AstroColors.RedNightSurfaceElevated,
    surfaceContainerHighest = AstroColors.RedNightPrimaryContainer,
    surfaceTint = AstroColors.RedNightPrimary,
    onSurface = AstroColors.RedNightTextPrimary,
    surfaceVariant = AstroColors.RedNightSurfaceElevated,
    onSurfaceVariant = AstroColors.RedNightTextSecondary,
    outline = AstroColors.RedNightOutline,
    outlineVariant = AstroColors.RedNightOutlineSubtle,
    scrim = AstroColors.RedNightScrim
)

val AstroShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AstroPhotoTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    veryDark: Boolean = false,
    redNight: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = when {
            !darkTheme -> DayObservatoryColorScheme
            redNight -> RedNightObservatoryColorScheme
            veryDark -> VeryDarkObservatoryColorScheme
            else -> NightObservatoryColorScheme
        },
        typography = AstroTypography,
        shapes = AstroShapes,
        content = content
    )
}
