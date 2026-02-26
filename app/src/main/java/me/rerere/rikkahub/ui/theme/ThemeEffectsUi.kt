package me.rerere.rikkahub.ui.theme

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.GradientStyle

@Composable
fun ThemeAtmosphereLayer(modifier: Modifier = Modifier) {
    val effects = LocalThemeEffects.current
    val colorScheme = MaterialTheme.colorScheme

    if (!effects.gradient.enabled && !effects.atmosphere.enabled) {
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val gradientBrush = if (effects.gradient.enabled) {
                    buildAtmosphereBrush(
                        style = effects.gradient.style,
                        startColor = Color(effects.gradient.startArgb.toInt()),
                        endColor = Color(effects.gradient.endArgb.toInt()),
                        colorScheme = colorScheme,
                        intensity = effects.gradient.intensity,
                        size = size,
                    )
                } else {
                    null
                }
                val topGlowBrush = if (effects.atmosphere.enabled && effects.atmosphere.topGlowIntensity > 0f) {
                    Brush.verticalGradient(
                        colors = listOf(
                            colorScheme.primary.copy(alpha = effects.atmosphere.topGlowIntensity),
                            Color.Transparent,
                        ),
                        startY = 0f,
                        endY = size.height * 0.45f,
                    )
                } else {
                    null
                }
                val vignetteBrush = if (effects.atmosphere.enabled && effects.atmosphere.vignetteIntensity > 0f) {
                    Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = effects.atmosphere.vignetteIntensity),
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.5f),
                        radius = size.maxDimension * 0.7f,
                    )
                } else {
                    null
                }
                onDrawBehind {
                    gradientBrush?.let { drawRect(brush = it) }
                    topGlowBrush?.let { drawRect(brush = it) }
                    vignetteBrush?.let { drawRect(brush = it) }
                }
            }
    )
}

@Composable
fun ThemeGlassContainer(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalThemeEffects.current.glass
    val density = LocalDensity.current

    val shouldRenderGlass = enabled && glass.enabled
    val tintColor = MaterialTheme.colorScheme.surface.copy(alpha = glass.tintOpacity)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = glass.borderOpacity)
    val blurModifier = remember(shouldRenderGlass, glass.blurDp, density) {
        if (shouldRenderGlass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && glass.blurDp > 0f) {
            val blurPx = with(density) { glass.blurDp.dp.toPx() }
            Modifier.graphicsLayer {
                renderEffect = RenderEffect
                    .createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                    .asComposeRenderEffect()
            }
        } else {
            Modifier
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .clipToBounds()
    ) {
        if (shouldRenderGlass) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(blurModifier)
                    .background(tintColor)
            )
            if (glass.borderOpacity > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, borderColor, shape)
                )
            }
        }
        content()
    }
}

private fun buildAtmosphereBrush(
    style: GradientStyle,
    startColor: Color,
    endColor: Color,
    colorScheme: androidx.compose.material3.ColorScheme,
    intensity: Float,
    size: androidx.compose.ui.geometry.Size,
): Brush {
    val safeIntensity = intensity.coerceIn(0f, 0.35f)
    val primary = colorScheme.primary.copy(alpha = safeIntensity)
    val secondary = colorScheme.secondary.copy(alpha = safeIntensity)
    val tertiary = colorScheme.tertiary.copy(alpha = safeIntensity)
    val from = startColor.copy(alpha = safeIntensity)
    val to = endColor.copy(alpha = safeIntensity)

    return when (style) {
        GradientStyle.LINEAR -> {
            Brush.linearGradient(
                colors = listOf(from, to),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        }

        GradientStyle.RADIAL -> {
            Brush.radialGradient(
                colors = listOf(from, primary, to),
                center = Offset(size.width * 0.35f, size.height * 0.18f),
                radius = size.maxDimension * 0.8f,
            )
        }

        GradientStyle.AURORA -> {
            Brush.linearGradient(
                colors = listOf(from, primary, tertiary, secondary, to),
                start = Offset(0f, 0f),
                end = Offset(size.width * 0.85f, size.height * 1.05f),
            )
        }
    }
}
