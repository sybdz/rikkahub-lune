package me.rerere.rikkahub.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.data.datastore.MotionStyle
import me.rerere.rikkahub.data.datastore.ThemeAtmosphereConfig
import me.rerere.rikkahub.data.datastore.ThemeGlassConfig
import me.rerere.rikkahub.data.datastore.ThemeGradientConfig
import me.rerere.rikkahub.data.datastore.ThemeMotionConfig
import me.rerere.rikkahub.data.datastore.ThemeProfile

@Immutable
data class ThemeEffects(
    val gradient: ThemeGradientConfig = ThemeGradientConfig(),
    val glass: ThemeGlassConfig = ThemeGlassConfig(),
    val atmosphere: ThemeAtmosphereConfig = ThemeAtmosphereConfig(),
)

@Stable
class AppMotion(
    val enabled: Boolean,
    val durationScale: Float,
    val style: MotionStyle,
) {
    fun duration(baseDurationMillis: Int, isExempt: Boolean = false): Int {
        if (!enabled || isExempt) {
            return baseDurationMillis
        }
        val styleFactor = when (style) {
            MotionStyle.GENTLE -> 1.15f
            MotionStyle.STANDARD -> 1.0f
            MotionStyle.BRISK -> 0.85f
        }
        return (baseDurationMillis * durationScale * styleFactor)
            .toInt()
            .coerceAtLeast(1)
    }

    fun easing(isExempt: Boolean = false): Easing {
        if (!enabled || isExempt) {
            return FastOutSlowInEasing
        }
        return when (style) {
            MotionStyle.GENTLE -> CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
            MotionStyle.STANDARD -> FastOutSlowInEasing
            MotionStyle.BRISK -> FastOutLinearInEasing
        }
    }

    fun <T> tweenSpec(
        baseDurationMillis: Int,
        delayMillis: Int = 0,
        isExempt: Boolean = false,
    ): TweenSpec<T> {
        return tween(
            durationMillis = duration(baseDurationMillis = baseDurationMillis, isExempt = isExempt),
            delayMillis = delayMillis,
            easing = easing(isExempt = isExempt),
        )
    }

    companion object {
        fun from(config: ThemeMotionConfig): AppMotion {
            return AppMotion(
                enabled = config.enabled,
                durationScale = config.durationScale,
                style = config.style,
            )
        }
    }
}

val LocalThemeEffects = staticCompositionLocalOf { ThemeEffects() }

val LocalAppMotion = staticCompositionLocalOf {
    AppMotion.from(ThemeMotionConfig())
}

object ThemeStudioDraftStore {
    private val draftProfileFlow = MutableStateFlow<ThemeProfile?>(null)

    val draftProfile: StateFlow<ThemeProfile?> = draftProfileFlow.asStateFlow()

    fun setDraftProfile(profile: ThemeProfile?) {
        draftProfileFlow.value = profile
    }

    fun clear() {
        draftProfileFlow.value = null
    }
}
