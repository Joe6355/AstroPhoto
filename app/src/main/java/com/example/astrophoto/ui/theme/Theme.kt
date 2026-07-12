package com.example.astrophoto.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val NightObservatoryColorScheme = darkColorScheme(
    primary = AstroColors.Primary,
    onPrimary = AstroColors.OnPrimary,
    primaryContainer = Color(0xFF302555),
    onPrimaryContainer = Color(0xFFE9E2FF),
    secondary = AstroColors.Secondary,
    onSecondary = AstroColors.OnSecondary,
    secondaryContainer = Color(0xFF153F49),
    onSecondaryContainer = Color(0xFFC5F4FC),
    tertiary = AstroColors.Success,
    onTertiary = Color(0xFF0B3921),
    error = AstroColors.Error,
    onError = Color(0xFF5F1412),
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
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NightObservatoryColorScheme,
        typography = AstroTypography,
        shapes = AstroShapes,
        content = content
    )
}
