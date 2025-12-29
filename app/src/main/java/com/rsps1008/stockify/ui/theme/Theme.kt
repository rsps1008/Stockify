package com.rsps1008.stockify.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

// Custom Colors for Stock Gain/Loss
data class StockColors(
    val gain: Color,
    val loss: Color
)

private val LightStockColors = StockColors(
    gain = StockGain,
    loss = StockLoss
)

private val DarkStockColors = StockColors(
    gain = StockGainDark,
    loss = StockLossDark
)

private val LocalStockColors = staticCompositionLocalOf { LightStockColors }

// Custom Theme object to hold custom colors
object StockifyAppTheme {
    val stockColors: StockColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStockColors.current
}

@Composable
fun StockifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val stockColors = if (darkTheme) DarkStockColors else LightStockColors

    CompositionLocalProvider(LocalStockColors provides stockColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
