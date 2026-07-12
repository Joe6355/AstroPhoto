package com.example.astrophoto.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val baseStyle = TextStyle(
    fontFamily = FontFamily.Default,
    color = AstroColors.TextPrimary,
    letterSpacing = 0.sp
)

val AstroTypography = Typography(
    displaySmall = baseStyle.copy(
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.SemiBold
    ),
    headlineSmall = baseStyle.copy(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = baseStyle.copy(
        fontSize = 21.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = baseStyle.copy(
        fontSize = 17.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleSmall = baseStyle.copy(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Medium
    ),
    bodyLarge = baseStyle.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = baseStyle.copy(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = baseStyle.copy(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = baseStyle.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold
    ),
    labelMedium = baseStyle.copy(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium
    ),
    labelSmall = baseStyle.copy(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    )
)
