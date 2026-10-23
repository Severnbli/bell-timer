package com.belltimer.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.belltimer.app.data.ModuleType
import com.belltimer.app.data.PackKind

// "Brass and ink": a warm paper/ink base with a brass accent, the colour of a
// school bell. Module types get their own hues so a timeline reads at a glance.
private val LightColors = lightColorScheme(
    primary = Color(0xFF8F5300),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCB8),
    onPrimaryContainer = Color(0xFF2D1600),
    secondary = Color(0xFF3D5BA9),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE2FF),
    onSecondaryContainer = Color(0xFF00184A),
    tertiary = Color(0xFF1F7A6E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFA6F2E3),
    onTertiaryContainer = Color(0xFF00201C),
    background = Color(0xFFF8F3EC),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFF8F3EC),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFEDE3D6),
    onSurfaceVariant = Color(0xFF52463A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFDF8F2),
    surfaceContainer = Color(0xFFF3EDE5),
    surfaceContainerHigh = Color(0xFFEDE6DD),
    surfaceContainerHighest = Color(0xFFE6DFD6),
    outline = Color(0xFF85766A),
    outlineVariant = Color(0xFFD8CBBD),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410001),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF4B562),
    onPrimary = Color(0xFF482900),
    primaryContainer = Color(0xFF673D00),
    onPrimaryContainer = Color(0xFFFFDCB8),
    secondary = Color(0xFFB4C4FF),
    onSecondary = Color(0xFF042C78),
    secondaryContainer = Color(0xFF23438F),
    onSecondaryContainer = Color(0xFFDCE2FF),
    tertiary = Color(0xFF8AD5C7),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005047),
    onTertiaryContainer = Color(0xFFA6F2E3),
    background = Color(0xFF15120E),
    onBackground = Color(0xFFEDE1D5),
    surface = Color(0xFF15120E),
    onSurface = Color(0xFFEDE1D5),
    surfaceVariant = Color(0xFF3A322A),
    onSurfaceVariant = Color(0xFFD6C6B5),
    surfaceContainerLowest = Color(0xFF100D0A),
    surfaceContainerLow = Color(0xFF1D1914),
    surfaceContainer = Color(0xFF221D18),
    surfaceContainerHigh = Color(0xFF2C2722),
    surfaceContainerHighest = Color(0xFF37322C),
    outline = Color(0xFF9F8F80),
    outlineVariant = Color(0xFF52463A),
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF680003),
    errorContainer = Color(0xFF930006),
    onErrorContainer = Color(0xFFFFDAD5),
)

/** Colours that Material does not name: states and pack kinds. */
@Immutable
data class ExtraColors(
    val success: Color,
    val warning: Color,
    val correction: Color,
    val correctionContainer: Color,
    val onCorrectionContainer: Color,
)

private val LightExtra = ExtraColors(
    success = Color(0xFF2E7D32),
    warning = Color(0xFFB26A00),
    correction = Color(0xFFC0432F),
    correctionContainer = Color(0xFFFFDAD2),
    onCorrectionContainer = Color(0xFF3E0500),
)

private val DarkExtra = ExtraColors(
    success = Color(0xFF8BD68F),
    warning = Color(0xFFFFB95C),
    correction = Color(0xFFFF8F7A),
    correctionContainer = Color(0xFF6D1D10),
    onCorrectionContainer = Color(0xFFFFDAD2),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val BaseTypography = Typography()

private val AppTypography = BaseTypography.copy(
    displayLarge = BaseTypography.displayLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
        letterSpacing = (-2).sp,
    ),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Times and countdowns use tabular digits so they do not jitter. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun BellTimerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}

object AppColors {
    val extra: ExtraColors
        @Composable get() = LocalExtraColors.current

    @Composable
    fun module(type: ModuleType): Color = when (type) {
        ModuleType.Lesson -> MaterialTheme.colorScheme.secondary
        ModuleType.Bell -> MaterialTheme.colorScheme.primary
        ModuleType.Series -> MaterialTheme.colorScheme.tertiary
    }

    @Composable
    fun moduleContainer(type: ModuleType): Color = when (type) {
        ModuleType.Lesson -> MaterialTheme.colorScheme.secondaryContainer
        ModuleType.Bell -> MaterialTheme.colorScheme.primaryContainer
        ModuleType.Series -> MaterialTheme.colorScheme.tertiaryContainer
    }

    @Composable
    fun onModuleContainer(type: ModuleType): Color = when (type) {
        ModuleType.Lesson -> MaterialTheme.colorScheme.onSecondaryContainer
        ModuleType.Bell -> MaterialTheme.colorScheme.onPrimaryContainer
        ModuleType.Series -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    @Composable
    fun pack(kind: PackKind): Color = when (kind) {
        PackKind.Base -> MaterialTheme.colorScheme.primary
        PackKind.Correction -> extra.correction
    }
}
