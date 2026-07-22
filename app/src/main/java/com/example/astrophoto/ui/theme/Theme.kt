package com.example.astrophoto.ui.theme

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
    surfaceVariant = AstroColors.Surface
)

val AstroShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AstroPhotoTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    veryDark: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = when {
            !darkTheme -> DayObservatoryColorScheme
            veryDark -> VeryDarkObservatoryColorScheme
            else -> NightObservatoryColorScheme
        },
        typography = AstroTypography,
        shapes = AstroShapes,
        content = content
    )
}
