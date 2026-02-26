package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import me.tatarka.google.material.hct.Hct
import me.rerere.rikkahub.data.datastore.MotionStyle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.ThemeMotionConfig
import me.rerere.rikkahub.data.datastore.ThemeProfile
import me.rerere.rikkahub.data.datastore.ThemeRoleTuningConfig
import me.rerere.rikkahub.data.datastore.ThemeStudioConfig
import me.rerere.rikkahub.data.datastore.activeProfileOrDefault
import me.rerere.rikkahub.data.datastore.defaultBalancedThemeProfile
import me.rerere.rikkahub.data.datastore.ensureValid
import me.rerere.rikkahub.data.datastore.normalized
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.uuid.Uuid

class ThemeStudioResolverTest {

    @Test
    fun ensureValidCreatesBalancedProfileWhenEmpty() {
        val normalized = ThemeStudioConfig().ensureValid("sakura")
        assertTrue(normalized.profiles.isNotEmpty())
        assertEquals("Balanced", normalized.profiles.first().name)
        assertEquals(normalized.profiles.first().id, normalized.activeProfileId)
    }

    @Test
    fun ensureValidRepairsInvalidActiveProfile() {
        val profile = defaultBalancedThemeProfile("ocean")
        val config = ThemeStudioConfig(
            activeProfileId = Uuid.random(),
            profiles = listOf(profile),
        )
        val normalized = config.ensureValid("sakura")
        assertEquals(profile.id, normalized.activeProfileId)
    }

    @Test
    fun configCanEncodeDecode() {
        val profile = defaultBalancedThemeProfile("spring")
        val config = ThemeStudioConfig(
            activeProfileId = profile.id,
            profiles = listOf(profile),
        )
        val json = JsonInstant.encodeToString(config)
        val decoded = JsonInstant.decodeFromString<ThemeStudioConfig>(json)
        assertEquals(config, decoded)
    }

    @Test
    fun resolverProducesReadableOnRoles() {
        val profile = ThemeProfile(
            id = Uuid.random(),
            name = "Test",
            basePresetId = "sakura",
            colorBlend = 1f,
            primarySeedArgb = 0xFFFF0000,
            secondarySeedArgb = 0xFF00FF00,
            tertiarySeedArgb = 0xFF0000FF,
        )
        val settings = Settings(
            dynamicColor = false,
            themeId = "sakura",
            themeStudio = ThemeStudioConfig(
                activeProfileId = profile.id,
                profiles = listOf(profile),
            )
        )

        val light = resolveThemeStudio(
            settings = settings,
            darkTheme = false,
            dynamicSchemeProvider = null,
            presetSchemeProvider = { id, dark -> findPresetTheme(id).getColorScheme(dark) },
        ).colorScheme

        val dark = resolveThemeStudio(
            settings = settings,
            darkTheme = true,
            dynamicSchemeProvider = null,
            presetSchemeProvider = { id, darkMode -> findPresetTheme(id).getColorScheme(darkMode) },
        ).colorScheme

        assertTrue(contrastRatio(light.onPrimary, light.primary) >= 4.5)
        assertTrue(contrastRatio(light.onSecondary, light.secondary) >= 4.5)
        assertTrue(contrastRatio(light.onTertiary, light.tertiary) >= 4.5)
        assertTrue(contrastRatio(light.onSurface, light.surface) >= 4.5)
        assertTrue(contrastRatio(light.onBackground, light.background) >= 4.5)
        assertTrue(contrastRatio(light.onPrimaryContainer, light.primaryContainer) >= 4.5)
        assertTrue(contrastRatio(light.onSecondaryContainer, light.secondaryContainer) >= 4.5)
        assertTrue(contrastRatio(light.onTertiaryContainer, light.tertiaryContainer) >= 4.5)

        assertTrue(contrastRatio(dark.onPrimary, dark.primary) >= 4.5)
        assertTrue(contrastRatio(dark.onSecondary, dark.secondary) >= 4.5)
        assertTrue(contrastRatio(dark.onTertiary, dark.tertiary) >= 4.5)
        assertTrue(contrastRatio(dark.onSurface, dark.surface) >= 4.5)
        assertTrue(contrastRatio(dark.onBackground, dark.background) >= 4.5)
        assertTrue(contrastRatio(dark.onPrimaryContainer, dark.primaryContainer) >= 4.5)
        assertTrue(contrastRatio(dark.onSecondaryContainer, dark.secondaryContainer) >= 4.5)
        assertTrue(contrastRatio(dark.onTertiaryContainer, dark.tertiaryContainer) >= 4.5)
    }

    @Test
    fun resolverMapsPrimaryToneByDarkMode() {
        val profile = ThemeProfile(
            id = Uuid.random(),
            basePresetId = "sakura",
            colorBlend = 1f,
            primarySeedArgb = 0xFF21A5FF,
        )
        val settings = Settings(
            dynamicColor = false,
            themeId = "sakura",
            themeStudio = ThemeStudioConfig(
                activeProfileId = profile.id,
                profiles = listOf(profile),
            )
        )

        val lightPrimary = resolveThemeStudio(
            settings = settings,
            darkTheme = false,
            dynamicSchemeProvider = null,
            presetSchemeProvider = { id, dark -> findPresetTheme(id).getColorScheme(dark) },
        ).colorScheme.primary

        val darkPrimary = resolveThemeStudio(
            settings = settings,
            darkTheme = true,
            dynamicSchemeProvider = null,
            presetSchemeProvider = { id, dark -> findPresetTheme(id).getColorScheme(dark) },
        ).colorScheme.primary

        val lightTone = Hct.fromInt(lightPrimary.toArgb()).tone
        val darkTone = Hct.fromInt(darkPrimary.toArgb()).tone

        assertTrue(lightTone in 36.0..44.0)
        assertTrue(darkTone in 76.0..84.0)
    }

    @Test
    fun profileNormalizationClampsValues() {
        val normalized = ThemeProfile(
            basePresetId = "",
            colorBlend = -0.2f,
            motion = ThemeMotionConfig(durationScale = 4f),
            roleTuning = ThemeRoleTuningConfig(
                surfaceToneShift = 50f,
                surfaceContainerToneShift = -99f,
                backgroundToneShift = 33f,
                primaryContainerToneShift = -40f,
                secondaryContainerToneShift = 26f,
                tertiaryContainerToneShift = -24f,
            ),
        ).normalized("ocean")

        assertEquals("ocean", normalized.basePresetId)
        assertEquals(0f, normalized.colorBlend)
        assertEquals(2.0f, normalized.motion.durationScale)
        assertEquals(20f, normalized.roleTuning.surfaceToneShift)
        assertEquals(-20f, normalized.roleTuning.surfaceContainerToneShift)
        assertEquals(20f, normalized.roleTuning.backgroundToneShift)
        assertEquals(-20f, normalized.roleTuning.primaryContainerToneShift)
        assertEquals(20f, normalized.roleTuning.secondaryContainerToneShift)
        assertEquals(-20f, normalized.roleTuning.tertiaryContainerToneShift)
    }

    @Test
    fun resolverAppliesRoleTuningToSurfaceAndContainers() {
        val profile = ThemeProfile(
            id = Uuid.random(),
            basePresetId = "sakura",
            colorBlend = 0f,
            roleTuning = ThemeRoleTuningConfig(
                enabled = true,
                surfaceToneShift = 8f,
                surfaceContainerToneShift = -8f,
                backgroundToneShift = 6f,
                primaryContainerToneShift = -10f,
            )
        )
        val settings = Settings(
            dynamicColor = false,
            themeId = "sakura",
            themeStudio = ThemeStudioConfig(
                activeProfileId = profile.id,
                profiles = listOf(profile),
            )
        )
        val resolved = resolveThemeStudio(
            settings = settings,
            darkTheme = false,
            dynamicSchemeProvider = null,
            presetSchemeProvider = { id, dark -> findPresetTheme(id).getColorScheme(dark) },
        ).colorScheme
        val base = findPresetTheme("sakura").getColorScheme(false)

        val resolvedSurfaceTone = Hct.fromInt(resolved.surface.toArgb()).tone
        val baseSurfaceTone = Hct.fromInt(base.surface.toArgb()).tone
        assertTrue(resolvedSurfaceTone > baseSurfaceTone)

        val resolvedContainerTone = Hct.fromInt(resolved.surfaceContainer.toArgb()).tone
        val baseContainerTone = Hct.fromInt(base.surfaceContainer.toArgb()).tone
        assertTrue(resolvedContainerTone < baseContainerTone)

        val resolvedPrimaryContainerTone = Hct.fromInt(resolved.primaryContainer.toArgb()).tone
        val basePrimaryContainerTone = Hct.fromInt(base.primaryContainer.toArgb()).tone
        assertTrue(resolvedPrimaryContainerTone < basePrimaryContainerTone)
    }

    @Test
    fun appMotionSupportsExemptDurations() {
        val motion = AppMotion.from(
            ThemeMotionConfig(
                enabled = true,
                durationScale = 1.5f,
                style = MotionStyle.BRISK,
            )
        )

        assertEquals(255, motion.duration(baseDurationMillis = 200, isExempt = false))
        assertEquals(200, motion.duration(baseDurationMillis = 200, isExempt = true))
        assertNotNull(motion.tweenSpec<Float>(200, isExempt = true))
    }

    @Test
    fun activeProfileFallbackWorks() {
        val profile = defaultBalancedThemeProfile("sakura")
        val config = ThemeStudioConfig(activeProfileId = null, profiles = listOf(profile))
        val active = config.activeProfileOrDefault("sakura")
        assertEquals(profile.id, active.id)
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
