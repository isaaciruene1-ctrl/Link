package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LinkDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = ElectricCyanContainerDark,
    onPrimaryContainer = ElectricCyan,
    secondary = SignalEmerald,
    onSecondary = CharcoalCanvas,
    secondaryContainer = SignalEmeraldContainerDark,
    onSecondaryContainer = SignalEmerald,
    tertiary = AmberPulse,
    onTertiary = CharcoalCanvas,
    tertiaryContainer = AmberPulseContainerDark,
    onTertiaryContainer = AmberPulse,
    background = CharcoalCanvas,
    onBackground = CrispWhite,
    surface = GraphiteSurface,
    onSurface = CrispWhite,
    surfaceVariant = GraphiteElevated,
    onSurfaceVariant = SoftSilver,
    surfaceContainerLowest = CharcoalCanvas,
    surfaceContainerLow = GraphiteSurface,
    surfaceContainer = GraphiteElevated,
    surfaceContainerHigh = GraphiteHighest,
    outline = GraphiteBorder,
    outlineVariant = MutedSlate,
    error = CrimsonAlert,
    onError = CrispWhite,
    errorContainer = CrimsonAlertContainerDark,
    onErrorContainer = CrimsonAlert
)

private val LinkLightColorScheme = lightColorScheme(
    primary = CobaltCyan,
    onPrimary = PureSurface,
    primaryContainer = CobaltCyanContainer,
    onPrimaryContainer = DeepObsidian,
    secondary = SignalEmerald,
    onSecondary = DeepObsidian,
    secondaryContainer = CobaltCyanContainer,
    onSecondaryContainer = DeepObsidian,
    tertiary = AmberPulse,
    onTertiary = DeepObsidian,
    background = AlabasterCanvas,
    onBackground = DeepObsidian,
    surface = PureSurface,
    onSurface = DeepObsidian,
    surfaceVariant = SlateContainer,
    onSurfaceVariant = SlateSecondary,
    surfaceContainerLowest = PureSurface,
    surfaceContainerLow = AlabasterCanvas,
    surfaceContainer = SlateContainer,
    surfaceContainerHigh = SlateBorder,
    outline = SlateBorder,
    outlineVariant = SlateSecondary,
    error = CrimsonAlert,
    onError = PureSurface
)

val LinkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun LinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> LinkDarkColorScheme
        else -> LinkLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LinkTypography,
        shapes = LinkShapes,
        content = content
    )
}
