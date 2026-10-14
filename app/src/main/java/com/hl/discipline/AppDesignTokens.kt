package com.hl.discipline

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** Material roles live in ColorScheme; these are the few app-specific surface roles. */
@Immutable
internal data class AppPalette(
    val dark: Boolean,
    val primaryTop: Color,
    val primaryBottom: Color,
    val primaryPressedTop: Color,
    val primaryPressedBottom: Color,
    val primaryDisabledTop: Color,
    val primaryDisabledBottom: Color,
    val primaryBorder: Color,
    val primaryHighlight: Color,
    val secondaryFill: Color,
    val secondaryPressed: Color,
    val secondaryDisabled: Color,
    val secondaryText: Color,
    val secondaryBorder: Color,
    val secondaryHighlight: Color,
    val glassButtonFill: Color,
    val glassButtonPressed: Color,
    val glassButtonBorder: Color,
    val iconPressed: Color,
    val iconBorder: Color,
    val disabledText: Color,
    val glassTintAlpha: Float,
    val glassBorder: Color,
    val focusDial: Color,
    val focusDialText: Color,
    val chartFocus: Color,
    val chartPresence: Color,
    val warningText: Color,
    val navigationFill: Color,
    val navigationInk: Color
)

private val LightPalette = AppPalette(
    dark = false,
    primaryTop = Color(0xFFC3E7DF), primaryBottom = Color(0xFFA9DCD1),
    primaryPressedTop = Color(0xFFA9DCD1), primaryPressedBottom = Color(0xFF91C9BD),
    primaryDisabledTop = Color(0xFFE3EBE8), primaryDisabledBottom = Color(0xFFDCE7E3),
    primaryBorder = Color(0x6680B9AE), primaryHighlight = Color(0x99FFFFFF),
    secondaryFill = Color(0xF5F1F7F5), secondaryPressed = Color(0xFFDCEFE9),
    secondaryDisabled = Color(0xFFE3EBE8), secondaryText = Color(0xFF46534F),
    secondaryBorder = Color(0xFFD8E4E0), secondaryHighlight = Color(0x66FFFFFF),
    glassButtonFill = Color(0xEDF7FAF9), glassButtonPressed = Color(0xFFDCEFE9),
    glassButtonBorder = Color(0xFFB8CDC7),
    iconPressed = Color(0xFFDCEFE9), iconBorder = Color(0xFFD8E4E0),
    disabledText = Color(0xFF52645D), glassTintAlpha = .92f,
    glassBorder = Color(0xFFD8E4E0),
    focusDial = Color(0xFF294540), focusDialText = Color(0xFFEFF6F3),
    chartFocus = Color(0xFF76BDB4), chartPresence = Color(0xFF91AFC8),
    warningText = Color(0xFF7E5B42),
    navigationFill = Color(0xFFDCEFE9), navigationInk = Color(0xFF3B7971)
)

private val DarkPalette = AppPalette(
    dark = true,
    primaryTop = Color(0xFFA8DBD0), primaryBottom = Color(0xFF92CFC3),
    primaryPressedTop = Color(0xFF92CFC3), primaryPressedBottom = Color(0xFF80BAAE),
    primaryDisabledTop = Color(0xFF2B3939), primaryDisabledBottom = Color(0xFF263536),
    primaryBorder = Color(0x66365F59), primaryHighlight = Color(0x59FFFFFF),
    secondaryFill = Color(0xF526393A), secondaryPressed = Color(0xFF30524E),
    secondaryDisabled = Color(0xFF2B3939), secondaryText = Color(0xFFCDDBD6),
    secondaryBorder = Color(0xFF536D68), secondaryHighlight = Color(0x26FFFFFF),
    glassButtonFill = Color(0xFF293D3D), glassButtonPressed = Color(0xFF30524E),
    glassButtonBorder = Color(0xFF536D68),
    iconPressed = Color(0xFF30524E), iconBorder = Color(0xFF3A5551),
    disabledText = Color(0xFFBDD0C9), glassTintAlpha = .92f,
    glassBorder = Color(0xFF3A5551),
    focusDial = Color(0xFF203638), focusDialText = Color(0xFFEFF6F3),
    chartFocus = Color(0xFF92CFC3), chartPresence = Color(0xFF9AB9D3),
    warningText = Color(0xFFF0CDAE),
    navigationFill = Color(0xFFA9DCD1), navigationInk = Color(0xFF263331)
)

internal val LocalAppPalette = staticCompositionLocalOf { LightPalette }

internal object AppColors {
    val mint = Color(0xFFA9DCD1)
    val ink = Color(0xFF263331)

    val current: AppPalette
        @Composable get() = LocalAppPalette.current
    fun palette(dark: Boolean): AppPalette = if (dark) DarkPalette else LightPalette

    fun scheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
        primary = Color(0xFFA9DCD1), onPrimary = ink, background = Color(0xFF111B1C),
        onBackground = Color(0xFFEFF6F3), surface = Color(0xFF293D3D),
        onSurface = Color(0xFFEFF6F3), surfaceVariant = Color(0xFF26393A),
        onSurfaceVariant = Color(0xFFA1B5AF), outline = Color(0xFF3A5551),
        secondary = Color(0xFF9AB9D3), onSecondary = ink,
        secondaryContainer = Color(0xFF30524E),
        onSecondaryContainer = Color(0xFFEFF6F3), tertiary = Color(0xFFC2B5DA),
        error = Color(0xFFF2C6C1), onError = Color(0xFF442825),
        errorContainer = Color(0xFF533936), onErrorContainer = Color(0xFFF2C6C1)
    ) else lightColorScheme(
        primary = Color(0xFF3B7971), onPrimary = Color.White,
        background = Color(0xFFF7FAF9), onBackground = ink,
        surface = Color.White, onSurface = ink,
        surfaceVariant = Color(0xFFF1F7F5), onSurfaceVariant = Color(0xFF5F706B),
        outline = Color(0xFFD8E4E0), secondary = Color(0xFF4F728F),
        onSecondary = Color.White, secondaryContainer = Color(0xFFDCEFE9),
        onSecondaryContainer = ink, tertiary = Color(0xFF796995),
        error = Color(0xFFA44440), onError = Color.White,
        errorContainer = Color(0xFFF8E7E5), onErrorContainer = Color(0xFFA44440)
    )
}

// Headings intentionally have their own family; no handwriting resource is currently bundled.
internal val TitleFontFamily: FontFamily = FontFamily.Serif

// Bundle Latin and Chinese glyphs together so an OEM's themed fallback cannot replace body text.
// The app's minSdk is 26, which supports this single variable font at every configured weight.
@OptIn(ExperimentalTextApi::class)
internal val BodyFontFamily = FontFamily(listOf(
    FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold
).map { weight ->
    Font(R.font.noto_sans_sc, weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
})

internal object AppTypography {
    val material = Typography(
        displayLarge = TextStyle(fontSize = 48.sp, lineHeight = 56.sp, fontFamily = TitleFontFamily),
        displayMedium = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontFamily = TitleFontFamily),
        displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontFamily = TitleFontFamily),
        headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, fontFamily = TitleFontFamily),
        headlineMedium = TextStyle(fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, fontFamily = TitleFontFamily),
        headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, fontFamily = BodyFontFamily),
        titleLarge = TextStyle(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, fontFamily = TitleFontFamily),
        titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, fontFamily = BodyFontFamily),
        titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = BodyFontFamily),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontFamily = BodyFontFamily),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontFamily = BodyFontFamily),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontFamily = BodyFontFamily),
        labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, fontFamily = BodyFontFamily),
        labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontFamily = BodyFontFamily),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontFamily = BodyFontFamily)
    )
    val primaryButton = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = BodyFontFamily)
    val secondaryButton = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, fontFamily = BodyFontFamily)
    val pill = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = BodyFontFamily)
    val number = TextStyle(fontFeatureSettings = "tnum", fontFamily = BodyFontFamily)
}

internal object AppSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val page = 20.dp
    val lg = 24.dp
    val xl = 32.dp
}

internal object AppShapes {
    val material = Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp)
    )
    val card = RoundedCornerShape(20.dp)
    val glass = RoundedCornerShape(24.dp)
    val primaryButton = RoundedCornerShape(20.dp)
    val secondaryButton = RoundedCornerShape(16.dp)
    val iconButton = RoundedCornerShape(16.dp)
    val pill = RoundedCornerShape(20.dp)
    val segment = RoundedCornerShape(14.dp)
    val segmentThumb = RoundedCornerShape(11.dp)
}

internal object AppElevation {
    val primaryButton = 4.dp
    val secondaryButton = 1.dp
    val pressed = 1.dp
    val flat = 0.dp
}

internal object AppButtonSize {
    val primaryHeight = 56.dp
    val primaryMinWidth = 156.dp
    val secondaryHeight = 48.dp
    val iconTarget = 50.dp
    val pillTarget = 48.dp
    val pillVisible = 40.dp
}

internal object AppIconSize {
    val button = 20.dp
    val secondaryButton = 19.dp
    val loading = 19.dp
    val navigation = 23.dp
    val action = 22.dp
}

internal object AppMotion {
    val easing = androidx.compose.animation.core.CubicBezierEasing(.2f, 0f, 0f, 1f)
    const val pressScale = .985f
    const val cardPressScale = .99f
    const val pressSpringStiffness = 650f
    const val pressSpringDamping = 1f
    // Feedback, state and spatial transitions share three clocks. Real timer interpolation
    // and the finite launch scene intentionally keep their own durations.
    const val feedback = 100
    const val state = 200
    const val spatial = 280
    const val outgoing = 100
    const val pressIn = feedback
    const val navigationIcon = state
    const val navigationSpringStiffness = 440f
    const val navigationSpringDamping = 1f
    val tabTravel = 8.dp
    val pageTravel = 12.dp
    const val title = state
    const val launchTitle = 280
    const val launchScene = 1000
    const val tabEnter = spatial
    const val tabExit = outgoing
    const val press = 115
    const val selection = state
    const val contentEnter = state
    const val contentExit = outgoing
    const val briefExit = outgoing
    const val expand = spatial
    const val fieldExpand = spatial
    const val collapse = spatial
    const val progress = 240
    const val pageEnter = spatial
    const val pageExit = spatial
    const val heroEnter = spatial
    const val taskShared = 300
    const val sharedContentExit = 60
    const val detailSummary = 190
    const val detailBody = 220
    const val scrollBar = state
    const val stateChange = state
    const val focusRingStep = 900
    const val focusRingReset = 320
    const val focusCompletion = 650
    const val statsNumber = 300
    const val statsBar = 300
    const val statsEmpty = state
    const val magneticTravelDp = 3f
    const val magneticSettle = spatial
    const val navigationSnapStiffness = 500f
    const val navigationSnapDamping = 1f
    const val handoff = 420
    const val handoffMark = 120
    const val chartSelection = state
    const val rangeChange = 300
    const val courseBounds = 320
    const val gridZoom = 220
    const val gridLabels = 100
    const val panel = spatial
}
