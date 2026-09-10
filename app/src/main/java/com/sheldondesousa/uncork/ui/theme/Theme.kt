package com.sheldondesousa.uncork.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val UncorkColors = lightColorScheme(
    primary = Wine,
    onPrimary = Parchment,
    background = Parchment,
    onBackground = Ink,
    surface = Parchment,
    onSurface = Ink,
    error = Wine,
    onError = Parchment,
    outline = Hairline,
)

@Composable
fun UncorkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UncorkColors,
        typography = MaterialTheme.typography.copy(
            displayLarge = TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 64.sp,
                lineHeight = 70.sp,
            ),
            bodyLarge = TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                lineHeight = 26.sp,
            ),
            labelMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            ),
        ),
        content = content,
    )
}

