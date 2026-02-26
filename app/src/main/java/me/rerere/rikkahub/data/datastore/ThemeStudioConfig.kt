package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ThemeStudioConfig(
    val activeProfileId: Uuid? = null,
    val profiles: List<ThemeProfile> = emptyList(),
)

@Serializable
data class ThemeProfile(
    val id: Uuid = Uuid.random(),
    val name: String = "Balanced",
    val basePresetId: String = "",
    val colorBlend: Float = 0f,
    val primarySeedArgb: Long? = null,
    val secondarySeedArgb: Long? = null,
    val tertiarySeedArgb: Long? = null,
    val gradient: ThemeGradientConfig = ThemeGradientConfig(),
    val glass: ThemeGlassConfig = ThemeGlassConfig(),
    val atmosphere: ThemeAtmosphereConfig = ThemeAtmosphereConfig(),
    val motion: ThemeMotionConfig = ThemeMotionConfig(),
    val roleTuning: ThemeRoleTuningConfig = ThemeRoleTuningConfig(),
)

@Serializable
data class ThemeGradientConfig(
    val enabled: Boolean = true,
    val style: GradientStyle = GradientStyle.AURORA,
    val startArgb: Long = 0xFF7AA2F7,
    val endArgb: Long = 0xFF9ECE6A,
    val intensity: Float = 0.12f,
)

@Serializable
data class ThemeGlassConfig(
    val enabled: Boolean = true,
    val blurDp: Float = 16f,
    val tintOpacity: Float = 0.18f,
    val borderOpacity: Float = 0.10f,
)

@Serializable
data class ThemeAtmosphereConfig(
    val enabled: Boolean = true,
    val vignetteIntensity: Float = 0.08f,
    val topGlowIntensity: Float = 0.10f,
)

@Serializable
data class ThemeMotionConfig(
    val enabled: Boolean = true,
    val durationScale: Float = 1f,
    val style: MotionStyle = MotionStyle.STANDARD,
)

@Serializable
data class ThemeRoleTuningConfig(
    val enabled: Boolean = true,
    val surfaceToneShift: Float = 0f,
    val surfaceContainerToneShift: Float = 0f,
    val backgroundToneShift: Float = 0f,
    val primaryContainerToneShift: Float = 0f,
    val secondaryContainerToneShift: Float = 0f,
    val tertiaryContainerToneShift: Float = 0f,
)

@Serializable
enum class GradientStyle {
    LINEAR,
    RADIAL,
    AURORA,
}

@Serializable
enum class MotionStyle {
    GENTLE,
    STANDARD,
    BRISK,
}

fun ThemeStudioConfig.ensureValid(fallbackPresetId: String): ThemeStudioConfig {
    val normalizedProfiles = profiles.map { it.normalized(fallbackPresetId) }
    if (normalizedProfiles.isEmpty()) {
        val profile = defaultBalancedThemeProfile(basePresetId = fallbackPresetId)
        return ThemeStudioConfig(
            activeProfileId = profile.id,
            profiles = listOf(profile),
        )
    }

    val normalizedActiveId = activeProfileId
        ?.takeIf { active -> normalizedProfiles.any { it.id == active } }
        ?: normalizedProfiles.first().id

    return copy(
        activeProfileId = normalizedActiveId,
        profiles = normalizedProfiles,
    )
}

fun ThemeStudioConfig.activeProfileOrDefault(fallbackPresetId: String): ThemeProfile {
    val normalized = ensureValid(fallbackPresetId)
    return normalized.profiles.first { it.id == normalized.activeProfileId }
}

fun defaultBalancedThemeProfile(basePresetId: String): ThemeProfile {
    return ThemeProfile(
        id = Uuid.random(),
        name = "Balanced",
        basePresetId = basePresetId,
        colorBlend = 0.30f,
        gradient = ThemeGradientConfig(
            enabled = true,
            style = GradientStyle.AURORA,
            startArgb = 0xFF7AA2F7,
            endArgb = 0xFF9ECE6A,
            intensity = 0.12f,
        ),
        glass = ThemeGlassConfig(
            enabled = true,
            blurDp = 16f,
            tintOpacity = 0.18f,
            borderOpacity = 0.10f,
        ),
        atmosphere = ThemeAtmosphereConfig(
            enabled = true,
            vignetteIntensity = 0.08f,
            topGlowIntensity = 0.10f,
        ),
        motion = ThemeMotionConfig(
            enabled = true,
            durationScale = 1.0f,
            style = MotionStyle.STANDARD,
        ),
        roleTuning = ThemeRoleTuningConfig(
            enabled = true,
            surfaceToneShift = 0f,
            surfaceContainerToneShift = 0f,
            backgroundToneShift = 0f,
            primaryContainerToneShift = 0f,
            secondaryContainerToneShift = 0f,
            tertiaryContainerToneShift = 0f,
        ),
    )
}

fun ThemeProfile.normalized(fallbackPresetId: String): ThemeProfile {
    return copy(
        basePresetId = basePresetId.ifBlank { fallbackPresetId },
        colorBlend = colorBlend.coerceIn(0f, 1f),
        gradient = gradient.normalized(),
        glass = glass.normalized(),
        atmosphere = atmosphere.normalized(),
        motion = motion.normalized(),
        roleTuning = roleTuning.normalized(),
    )
}

fun ThemeGradientConfig.normalized(): ThemeGradientConfig {
    return copy(intensity = intensity.coerceIn(0f, 0.35f))
}

fun ThemeGlassConfig.normalized(): ThemeGlassConfig {
    return copy(
        blurDp = blurDp.coerceIn(0f, 30f),
        tintOpacity = tintOpacity.coerceIn(0f, 0.35f),
        borderOpacity = borderOpacity.coerceIn(0f, 0.25f),
    )
}

fun ThemeAtmosphereConfig.normalized(): ThemeAtmosphereConfig {
    return copy(
        vignetteIntensity = vignetteIntensity.coerceIn(0f, 0.25f),
        topGlowIntensity = topGlowIntensity.coerceIn(0f, 0.25f),
    )
}

fun ThemeMotionConfig.normalized(): ThemeMotionConfig {
    return copy(durationScale = durationScale.coerceIn(0.5f, 2.0f))
}

fun ThemeRoleTuningConfig.normalized(): ThemeRoleTuningConfig {
    return copy(
        surfaceToneShift = surfaceToneShift.coerceIn(-20f, 20f),
        surfaceContainerToneShift = surfaceContainerToneShift.coerceIn(-20f, 20f),
        backgroundToneShift = backgroundToneShift.coerceIn(-20f, 20f),
        primaryContainerToneShift = primaryContainerToneShift.coerceIn(-20f, 20f),
        secondaryContainerToneShift = secondaryContainerToneShift.coerceIn(-20f, 20f),
        tertiaryContainerToneShift = tertiaryContainerToneShift.coerceIn(-20f, 20f),
    )
}
