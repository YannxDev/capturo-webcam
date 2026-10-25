package io.github.yannick.capturowebcam.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.yannick.capturowebcam.data.SettingsStore

data class AccentChoice(
    val name: String,
    val light: Color,
    val dark: Color,
)

val AccentChoices = listOf(
    AccentChoice("Bleu", Color(0xFF2563EB), Color(0xFF2563EB)),
    AccentChoice("Violet", Color(0xFF7C3AED), Color(0xFF7C3AED)),
    AccentChoice("Rose", Color(0xFFDB2777), Color(0xFFDB2777)),
    AccentChoice("Rouge", Color(0xFFDC2626), Color(0xFFDC2626)),
    AccentChoice("Orange", Color(0xFFEA580C), Color(0xFFEA580C)),
    AccentChoice("Jaune", Color(0xFFCA8A04), Color(0xFFCA8A04)),
    AccentChoice("Vert", Color(0xFF16A34A), Color(0xFF16A34A)),
    AccentChoice("Turquoise", Color(0xFF0D9488), Color(0xFF0D9488)),
    AccentChoice("Gris", Color(0xFF52525B), Color(0xFF52525B)),
)

internal fun readableOn(background: Color): Color {
    val l = background.luminance()
    val blackContrast = (l + 0.05f) / 0.05f
    val whiteContrast = 1.05f / (l + 0.05f)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

private fun buildScheme(dark: Boolean, accent: AccentChoice) = run {
    // La même couleur vive est utilisée en clair et en sombre : le mode
    // sombre ne doit pas délaver l'accent choisi.
    val primary = accent.light
    val onPrimary = readableOn(primary)
    val container = primary
    val onContainer = onPrimary
    val surface = if (dark) Color(0xFF101014) else Color(0xFFF7F7FB)

    if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = primary,
            onSecondary = onPrimary,
            secondaryContainer = container,
            onSecondaryContainer = onContainer,
            tertiary = primary,
            onTertiary = onPrimary,
            surface = surface,
            background = surface,
            surfaceTint = primary,
            surfaceContainerLow = Color(0xFF17171C),
            surfaceContainer = Color(0xFF1B1B21),
            surfaceContainerHigh = Color(0xFF22222A),
            surfaceContainerHighest = Color(0xFF2A2A33),
            surfaceVariant = Color(0xFF26262E),
            onSurfaceVariant = Color(0xFFB9B9C6),
            outline = Color(0xFF464652),
            outlineVariant = Color(0xFF2C2C36),
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = primary,
            onSecondary = onPrimary,
            secondaryContainer = container,
            onSecondaryContainer = onContainer,
            tertiary = primary,
            onTertiary = onPrimary,
            surface = surface,
            background = surface,
            surfaceTint = primary,
            surfaceContainerLow = Color.White,
            surfaceContainer = Color(0xFFF1F1F6),
            surfaceContainerHigh = Color(0xFFEAEAF0),
            surfaceContainerHighest = Color(0xFFE3E3EB),
            surfaceVariant = Color(0xFFE8E8F0),
            onSurfaceVariant = Color(0xFF55555F),
            outline = Color(0xFFC5C5D0),
            outlineVariant = Color(0xFFE2E2EA),
        )
    }
}

@Composable
fun CapturoTheme(
    themeMode: Int,
    accentIndex: Int,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        SettingsStore.THEME_LIGHT -> false
        SettingsStore.THEME_DARK -> true
        else -> systemDark
    }
    val accent = AccentChoices.getOrElse(accentIndex) { AccentChoices.first() }
    val colorScheme = remember(dark, accent) { buildScheme(dark, accent) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
