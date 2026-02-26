package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import me.tatarka.google.material.hct.Hct
import me.tatarka.google.material.palettes.TonalPalette
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.ThemeProfile
import me.rerere.rikkahub.data.datastore.activeProfileOrDefault
import me.rerere.rikkahub.data.datastore.ensureValid
import me.rerere.rikkahub.data.datastore.normalized
import kotlin.math.max
import kotlin.math.min

private const val MIN_ON_COLOR_CONTRAST = 4.5

data class ResolvedThemeStudio(
    val colorScheme: ColorScheme,
    val effects: ThemeEffects,
    val motion: AppMotion,
    val activeProfile: ThemeProfile,
)

fun resolveThemeStudio(
    settings: Settings,
    darkTheme: Boolean,
    dynamicSchemeProvider: (() -> ColorScheme)?,
    presetSchemeProvider: (presetId: String, dark: Boolean) -> ColorScheme,
    draftProfile: ThemeProfile? = null,
): ResolvedThemeStudio {
    val normalizedStudio = settings.themeStudio.ensureValid(settings.themeId)
    val activeProfile = (draftProfile ?: normalizedStudio.activeProfileOrDefault(settings.themeId))
        .normalized(fallbackPresetId = settings.themeId)

    val baseColorScheme = when {
        settings.dynamicColor && dynamicSchemeProvider != null -> dynamicSchemeProvider()
        else -> presetSchemeProvider(activeProfile.basePresetId, darkTheme)
    }

    val blended = applyProfileColorBlend(
        base = baseColorScheme,
        profile = activeProfile,
        darkTheme = darkTheme,
    )
    val roleAdjusted = applyRoleTuning(
        scheme = blended,
        profile = activeProfile,
    )

    val readable = enforceReadableRoles(
        base = baseColorScheme,
        scheme = roleAdjusted,
    )

    return ResolvedThemeStudio(
        colorScheme = readable,
        effects = ThemeEffects(
            gradient = activeProfile.gradient,
            glass = activeProfile.glass,
            atmosphere = activeProfile.atmosphere,
        ),
        motion = AppMotion.from(activeProfile.motion),
        activeProfile = activeProfile,
    )
}

private data class AccentGroup(
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val accentFixed: Color,
    val accentFixedDim: Color,
    val onAccentFixed: Color,
    val onAccentFixedVariant: Color,
    val inverseAccent: Color,
)

private data class ToneSet(
    val accent: Int,
    val onAccent: Int,
    val accentContainer: Int,
    val onAccentContainer: Int,
    val accentFixed: Int,
    val accentFixedDim: Int,
    val onAccentFixed: Int,
    val onAccentFixedVariant: Int,
    val inverseAccent: Int,
)

private fun tones(darkTheme: Boolean): ToneSet {
    return if (darkTheme) {
        ToneSet(
            accent = 80,
            onAccent = 20,
            accentContainer = 30,
            onAccentContainer = 90,
            accentFixed = 90,
            accentFixedDim = 80,
            onAccentFixed = 10,
            onAccentFixedVariant = 30,
            inverseAccent = 40,
        )
    } else {
        ToneSet(
            accent = 40,
            onAccent = 100,
            accentContainer = 90,
            onAccentContainer = 10,
            accentFixed = 90,
            accentFixedDim = 80,
            onAccentFixed = 10,
            onAccentFixedVariant = 30,
            inverseAccent = 80,
        )
    }
}

private fun applyProfileColorBlend(
    base: ColorScheme,
    profile: ThemeProfile,
    darkTheme: Boolean,
): ColorScheme {
    val blend = profile.colorBlend
    if (blend <= 0f) {
        return base
    }

    val primary = profile.primarySeedArgb?.let {
        blendAccentGroup(
            baseAccent = base.primary,
            seedArgb = it,
            blend = blend,
            darkTheme = darkTheme,
        )
    }

    val secondary = profile.secondarySeedArgb?.let {
        blendAccentGroup(
            baseAccent = base.secondary,
            seedArgb = it,
            blend = blend,
            darkTheme = darkTheme,
        )
    }

    val tertiary = profile.tertiarySeedArgb?.let {
        blendAccentGroup(
            baseAccent = base.tertiary,
            seedArgb = it,
            blend = blend,
            darkTheme = darkTheme,
        )
    }

    return base.copy(
        primary = primary?.accent ?: base.primary,
        onPrimary = primary?.onAccent ?: base.onPrimary,
        primaryContainer = primary?.accentContainer ?: base.primaryContainer,
        onPrimaryContainer = primary?.onAccentContainer ?: base.onPrimaryContainer,
        primaryFixed = primary?.accentFixed ?: base.primaryFixed,
        primaryFixedDim = primary?.accentFixedDim ?: base.primaryFixedDim,
        onPrimaryFixed = primary?.onAccentFixed ?: base.onPrimaryFixed,
        onPrimaryFixedVariant = primary?.onAccentFixedVariant ?: base.onPrimaryFixedVariant,
        inversePrimary = primary?.inverseAccent ?: base.inversePrimary,
        secondary = secondary?.accent ?: base.secondary,
        onSecondary = secondary?.onAccent ?: base.onSecondary,
        secondaryContainer = secondary?.accentContainer ?: base.secondaryContainer,
        onSecondaryContainer = secondary?.onAccentContainer ?: base.onSecondaryContainer,
        secondaryFixed = secondary?.accentFixed ?: base.secondaryFixed,
        secondaryFixedDim = secondary?.accentFixedDim ?: base.secondaryFixedDim,
        onSecondaryFixed = secondary?.onAccentFixed ?: base.onSecondaryFixed,
        onSecondaryFixedVariant = secondary?.onAccentFixedVariant ?: base.onSecondaryFixedVariant,
        tertiary = tertiary?.accent ?: base.tertiary,
        onTertiary = tertiary?.onAccent ?: base.onTertiary,
        tertiaryContainer = tertiary?.accentContainer ?: base.tertiaryContainer,
        onTertiaryContainer = tertiary?.onAccentContainer ?: base.onTertiaryContainer,
        tertiaryFixed = tertiary?.accentFixed ?: base.tertiaryFixed,
        tertiaryFixedDim = tertiary?.accentFixedDim ?: base.tertiaryFixedDim,
        onTertiaryFixed = tertiary?.onAccentFixed ?: base.onTertiaryFixed,
        onTertiaryFixedVariant = tertiary?.onAccentFixedVariant ?: base.onTertiaryFixedVariant,
    )
}

private fun blendAccentGroup(
    baseAccent: Color,
    seedArgb: Long,
    blend: Float,
    darkTheme: Boolean,
): AccentGroup {
    val baseHct = Hct.fromInt(baseAccent.toArgb())
    val seedHct = Hct.fromInt(seedArgb.toInt())

    val hue = lerpHue(
        start = baseHct.hue,
        end = seedHct.hue,
        fraction = blend,
    )
    val chroma = lerpDouble(
        start = baseHct.chroma,
        end = seedHct.chroma,
        fraction = blend,
    ).coerceAtLeast(4.0)

    val palette = TonalPalette.fromHueAndChroma(hue, chroma)
    val tones = tones(darkTheme)

    return AccentGroup(
        accent = Color(palette.tone(tones.accent)),
        onAccent = Color(palette.tone(tones.onAccent)),
        accentContainer = Color(palette.tone(tones.accentContainer)),
        onAccentContainer = Color(palette.tone(tones.onAccentContainer)),
        accentFixed = Color(palette.tone(tones.accentFixed)),
        accentFixedDim = Color(palette.tone(tones.accentFixedDim)),
        onAccentFixed = Color(palette.tone(tones.onAccentFixed)),
        onAccentFixedVariant = Color(palette.tone(tones.onAccentFixedVariant)),
        inverseAccent = Color(palette.tone(tones.inverseAccent)),
    )
}

private fun enforceReadableRoles(
    base: ColorScheme,
    scheme: ColorScheme,
): ColorScheme {
    val onPrimary = fixContrast(
        foreground = scheme.onPrimary,
        background = scheme.primary,
        minContrast = MIN_ON_COLOR_CONTRAST,
        fallback = base.onPrimary,
    )
    val onSecondary = fixContrast(
        foreground = scheme.onSecondary,
        background = scheme.secondary,
        minContrast = MIN_ON_COLOR_CONTRAST,
        fallback = base.onSecondary,
    )
    val onTertiary = fixContrast(
        foreground = scheme.onTertiary,
        background = scheme.tertiary,
        minContrast = MIN_ON_COLOR_CONTRAST,
        fallback = base.onTertiary,
    )
    val onSurface = fixContrast(
        foreground = scheme.onSurface,
        background = scheme.surface,
        minContrast = MIN_ON_COLOR_CONTRAST,
        fallback = base.onSurface,
    )
    val onBackground = fixContrast(
        foreground = scheme.onBackground,
        background = scheme.background,
        minContrast = MIN_ON_COLOR_CONTRAST,
        fallback = base.onBackground,
    )
    val onPrimaryContainer = fixContrast(
        foreground = scheme.onPrimaryContainer,
        background = scheme.primaryContainer,
        minContrast = MIN_ON_COLOR_CONTRAST,
        fallback = base.onPrimaryContainer,
    )
    val onSecondaryContainer = fixContrast(
        foreground = scheme.onSecondaryContainer,
        background = scheme.secondaryContainer,
        minContrast = MIN_ON_COLOR_CONTRAST,
        fallback = base.onSecondaryContainer,
    )
    val onTertiaryContainer = fixContrast(
        foreground = scheme.onTertiaryContainer,
        background = scheme.tertiaryContainer,
        minContrast = MIN_ON_COLOR_CONTRAST,
        fallback = base.onTertiaryContainer,
    )

    return scheme.copy(
        onPrimary = onPrimary,
        onSecondary = onSecondary,
        onTertiary = onTertiary,
        onSurface = onSurface,
        onBackground = onBackground,
        onPrimaryContainer = onPrimaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        onTertiaryContainer = onTertiaryContainer,
    )
}

private fun applyRoleTuning(
    scheme: ColorScheme,
    profile: ThemeProfile,
): ColorScheme {
    val roleTuning = profile.roleTuning.normalized()
    if (!roleTuning.enabled) {
        return scheme
    }

    return scheme.copy(
        background = shiftTone(scheme.background, roleTuning.backgroundToneShift),
        surface = shiftTone(scheme.surface, roleTuning.surfaceToneShift),
        surfaceBright = shiftTone(scheme.surfaceBright, roleTuning.surfaceToneShift),
        surfaceDim = shiftTone(scheme.surfaceDim, roleTuning.surfaceToneShift),
        surfaceContainerLowest = shiftTone(scheme.surfaceContainerLowest, roleTuning.surfaceContainerToneShift),
        surfaceContainerLow = shiftTone(scheme.surfaceContainerLow, roleTuning.surfaceContainerToneShift),
        surfaceContainer = shiftTone(scheme.surfaceContainer, roleTuning.surfaceContainerToneShift),
        surfaceContainerHigh = shiftTone(scheme.surfaceContainerHigh, roleTuning.surfaceContainerToneShift),
        surfaceContainerHighest = shiftTone(scheme.surfaceContainerHighest, roleTuning.surfaceContainerToneShift),
        primaryContainer = shiftTone(scheme.primaryContainer, roleTuning.primaryContainerToneShift),
        secondaryContainer = shiftTone(scheme.secondaryContainer, roleTuning.secondaryContainerToneShift),
        tertiaryContainer = shiftTone(scheme.tertiaryContainer, roleTuning.tertiaryContainerToneShift),
    )
}

private fun shiftTone(color: Color, shift: Float): Color {
    if (shift == 0f) {
        return color
    }
    val hct = Hct.fromInt(color.toArgb())
    val shiftedTone = (hct.tone + shift).coerceIn(0.0, 100.0)
    return Color(Hct.from(hct.hue, hct.chroma, shiftedTone).toInt())
}

private fun fixContrast(
    foreground: Color,
    background: Color,
    minContrast: Double,
    fallback: Color,
): Color {
    if (contrastRatio(foreground, background) >= minContrast) {
        return foreground
    }

    findReadableByTonalShift(
        foreground = foreground,
        background = background,
        minContrast = minContrast,
    )?.let { shifted ->
        return shifted
    }

    return if (contrastRatio(fallback, background) >= minContrast) {
        fallback
    } else {
        foreground
    }
}

private fun findReadableByTonalShift(
    foreground: Color,
    background: Color,
    minContrast: Double,
): Color? {
    val hct = Hct.fromInt(foreground.toArgb())

    for (step in 1..70) {
        val darkerTone = (hct.tone - step).coerceIn(0.0, 100.0)
        val lighterTone = (hct.tone + step).coerceIn(0.0, 100.0)

        val darker = Color(Hct.from(hct.hue, hct.chroma, darkerTone).toInt())
        val lighter = Color(Hct.from(hct.hue, hct.chroma, lighterTone).toInt())

        val darkerContrast = contrastRatio(darker, background)
        if (darkerContrast >= minContrast) {
            return darker
        }

        val lighterContrast = contrastRatio(lighter, background)
        if (lighterContrast >= minContrast) {
            return lighter
        }
    }

    return null
}

private fun contrastRatio(a: Color, b: Color): Double {
    val l1 = a.luminance().toDouble()
    val l2 = b.luminance().toDouble()
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun lerpDouble(start: Double, end: Double, fraction: Float): Double {
    return start + (end - start) * fraction
}

private fun lerpHue(start: Double, end: Double, fraction: Float): Double {
    val delta = ((end - start + 540.0) % 360.0) - 180.0
    val hue = (start + delta * fraction) % 360.0
    return if (hue < 0.0) hue + 360.0 else hue
}
