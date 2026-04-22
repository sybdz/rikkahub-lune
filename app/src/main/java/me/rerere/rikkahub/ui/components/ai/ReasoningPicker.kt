package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.resolveOpenAIChatCompletionsReasoningEffort
import me.rerere.ai.core.resolveOpenAIResponsesReasoningEffort
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningHigh
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningLow
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningMedium
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.roundToInt

private val levels = ReasoningLevel.entries
private val levelCount = levels.size
private val siliconflowThinkingModels = setOf(
    "Pro/moonshotai/Kimi-K2.5",
    "Pro/zai-org/GLM-5",
    "Pro/zai-org/GLM-5.1",
    "Pro/zai-org/GLM-4.7",
    "deepseek-ai/DeepSeek-V3.2",
    "Pro/deepseek-ai/DeepSeek-V3.2",
    "Qwen/Qwen3.5-397B-A17B",
    "Qwen/Qwen3.5-122B-A10B",
    "Qwen/Qwen3.5-35B-A3B",
    "Qwen/Qwen3.5-27B",
    "Qwen/Qwen3.5-9B",
    "Qwen/Qwen3.5-4B",
    "zai-org/GLM-4.6",
    "Qwen/Qwen3-8B",
    "Qwen/Qwen3-14B",
    "Qwen/Qwen3-32B",
    "Qwen/Qwen3-30B-A3B",
    "tencent/Hunyuan-A13B-Instruct",
    "zai-org/GLM-4.5V",
    "deepseek-ai/DeepSeek-V3.1-Terminus",
    "Pro/deepseek-ai/DeepSeek-V3.1-Terminus",
)

@Composable
fun ReasoningButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
    openAIReasoningEffort: String = "",
    model: Model? = null,
    provider: ProviderSetting? = null,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ReasoningPicker(
            reasoningLevel = reasoningLevel,
            openAIReasoningEffort = openAIReasoningEffort,
            model = model,
            provider = provider,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningLevel = onUpdateReasoningLevel,
        )
    }

    ToggleSurface(
        checked = reasoningLevel.isEnabled,
        onClick = { showPicker = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                ReasoningIcon(reasoningLevel)
            }
            if (!onlyIcon) Text(stringResource(R.string.setting_provider_page_reasoning))
        }
    }
}

@Composable
fun ReasoningPicker(
    reasoningLevel: ReasoningLevel,
    openAIReasoningEffort: String = "",
    model: Model? = null,
    provider: ProviderSetting? = null,
    onDismissRequest: () -> Unit = {},
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    val currentIndex = levels.indexOf(reasoningLevel).coerceAtLeast(0)
    val notSent = stringResource(R.string.assistant_page_openai_reasoning_effort_not_sent)
    val effectiveChatCompletionsEffort = resolveOpenAIChatCompletionsReasoningEffort(
        thinkingBudget = reasoningLevel.budgetTokens,
        overrideEffort = openAIReasoningEffort
    ) ?: notSent
    val effectiveResponsesEffort = resolveOpenAIResponsesReasoningEffort(
        thinkingBudget = reasoningLevel.budgetTokens,
        overrideEffort = openAIReasoningEffort
    ) ?: notSent
    val previews = buildReasoningParamPreviews(
        reasoningLevel = reasoningLevel,
        openAIChatEffort = effectiveChatCompletionsEffort,
        openAIResponsesEffort = effectiveResponsesEffort,
        provider = provider,
        model = model,
    )
    var sliderValue by remember { mutableFloatStateOf(currentIndex.toFloat()) }

    LaunchedEffect(currentIndex) {
        sliderValue = currentIndex.toFloat()
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 标题
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.reasoning_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.reasoning_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // 当前等级展示
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val iconColor by animateColorAsState(
                    if (reasoningLevel.isEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = when (reasoningLevel) {
                        ReasoningLevel.OFF -> HugeIcons.Idea
                        ReasoningLevel.AUTO -> HugeIcons.Idea01
                        ReasoningLevel.LOW -> ReasoningLow
                        ReasoningLevel.MEDIUM -> ReasoningMedium
                        ReasoningLevel.HIGH -> ReasoningHigh
                        ReasoningLevel.XHIGH -> ReasoningHigh
                    },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = iconColor,
                )
                Text(
                    text = reasoningLevel.label(),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        val snappedIndex = sliderValue.roundToInt().coerceIn(0, levelCount - 1)
                        sliderValue = snappedIndex.toFloat()
                        onUpdateReasoningLevel(levels[snappedIndex])
                    },
                    valueRange = 0f..(levelCount - 1).toFloat(),
                    steps = levelCount - 2,
                    modifier = Modifier.fillMaxWidth(),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onPrimary)
                            )
                        }
                    },
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            drawStopIndicator = null,
                            thumbTrackGapSize = 0.dp,
                        )
                    }
                )

                ReasoningScale(
                    selectedLevel = reasoningLevel,
                    onSelect = { level ->
                        sliderValue = levels.indexOf(level).toFloat()
                        onUpdateReasoningLevel(level)
                    }
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_reasoning_request_params),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        previews.chunked(2).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowItems.forEach { preview ->
                                    ReasoningParamChip(
                                        label = preview.label,
                                        value = preview.value,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (rowItems.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningParamChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class ReasoningParamPreview(
    val label: String,
    val value: String,
)

private fun buildReasoningParamPreviews(
    reasoningLevel: ReasoningLevel,
    openAIChatEffort: String,
    openAIResponsesEffort: String,
    provider: ProviderSetting?,
    model: Model?,
): List<ReasoningParamPreview> = buildList {
    currentCompatPreview(
        reasoningLevel = reasoningLevel,
        provider = provider,
        model = model,
    )?.let(::add)
    add(ReasoningParamPreview("OpenAI Chat", "reasoning_effort=$openAIChatEffort"))
    add(ReasoningParamPreview("Responses", "reasoning.effort=$openAIResponsesEffort"))
    add(ReasoningParamPreview("Claude", reasoningLevel.toClaudePreview()))
    add(ReasoningParamPreview("Gemini 3", reasoningLevel.toGemini3Preview()))
    add(ReasoningParamPreview("Gemini 2.5 Pro", reasoningLevel.toGemini25ProPreview()))
    add(ReasoningParamPreview("Gemini 2.5 Flash", reasoningLevel.toGemini25FlashPreview()))
}

private fun currentCompatPreview(
    reasoningLevel: ReasoningLevel,
    provider: ProviderSetting?,
    model: Model?,
): ReasoningParamPreview? {
    val openAIProvider = provider as? ProviderSetting.OpenAI ?: return null
    if (openAIProvider.useResponseApi) return null
    val host = openAIProvider.baseUrl.toHttpUrlOrNull()?.host ?: return null
    return when (host) {
        "openrouter.ai" -> ReasoningParamPreview("Current · OpenRouter", reasoningLevel.toOpenRouterPreview())
        "dashscope.aliyuncs.com" -> ReasoningParamPreview("Current · DashScope", reasoningLevel.toDashScopePreview())
        "ark.cn-beijing.volces.com" -> ReasoningParamPreview("Current · Ark", reasoningLevel.toToggleThinkingPreview())
        "chat.intern-ai.org.cn" -> ReasoningParamPreview("Current · InternLM", reasoningLevel.toInternLmPreview())
        "api.siliconflow.cn" -> ReasoningParamPreview("Current · SiliconFlow", reasoningLevel.toSiliconFlowPreview(model))
        "open.bigmodel.cn" -> ReasoningParamPreview("Current · GLM", reasoningLevel.toToggleThinkingPreview())
        "api.moonshot.cn" -> ReasoningParamPreview("Current · Moonshot", reasoningLevel.toToggleThinkingPreview())
        "api.mistral.ai" -> ReasoningParamPreview("Current · Mistral", "unsupported")
        else -> null
    }
}

private fun ReasoningLevel.toClaudePreview(): String = when (this) {
    ReasoningLevel.OFF -> "thinking.type=disabled"
    ReasoningLevel.AUTO -> "thinking.type=adaptive"
    else -> "thinking.type=adaptive · effort=$effort"
}

private fun ReasoningLevel.toGemini3Preview(): String = when (this) {
    ReasoningLevel.AUTO -> "default"
    ReasoningLevel.OFF -> "thinkingLevel=minimal"
    ReasoningLevel.LOW -> "thinkingLevel=low"
    ReasoningLevel.MEDIUM -> "thinkingLevel=medium"
    else -> "thinkingLevel=high"
}

private fun ReasoningLevel.toGemini25ProPreview(): String = when (this) {
    ReasoningLevel.AUTO, ReasoningLevel.OFF -> "default"
    else -> "thinkingBudget=$budgetTokens"
}

private fun ReasoningLevel.toGemini25FlashPreview(): String = when (this) {
    ReasoningLevel.AUTO -> "default"
    ReasoningLevel.OFF -> "thinkingBudget=0"
    else -> "thinkingBudget=$budgetTokens"
}

private fun ReasoningLevel.toOpenRouterPreview(): String = when (this) {
    ReasoningLevel.OFF -> "reasoning.effort=none"
    ReasoningLevel.AUTO -> "reasoning.enabled=true"
    else -> "reasoning.effort=$effort"
}

private fun ReasoningLevel.toDashScopePreview(): String = when (this) {
    ReasoningLevel.AUTO -> "enable_thinking=true"
    else -> "enable_thinking=$isEnabled · budget=$budgetTokens"
}

private fun ReasoningLevel.toToggleThinkingPreview(): String =
    "thinking=${if (isEnabled) "enabled" else "disabled"}"

private fun ReasoningLevel.toInternLmPreview(): String =
    "thinking_mode=$isEnabled"

private fun ReasoningLevel.toSiliconFlowPreview(model: Model?): String {
    if (model != null && model.modelId !in siliconflowThinkingModels) {
        return "enable_thinking=model-dependent"
    }
    return "enable_thinking=$isEnabled"
}

@Composable
private fun ReasoningScale(
    selectedLevel: ReasoningLevel,
    onSelect: (ReasoningLevel) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        levels.forEach { level ->
            val selected = level == selectedLevel
            val tickColor by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant
            )
            val labelColor by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToggleSurface(
                    checked = selected,
                    onClick = { onSelect(level) },
                    modifier = Modifier,
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(if (selected) 20.dp else 16.dp)
                                .height(if (selected) 6.dp else 4.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(tickColor)
                        )
                        Text(
                            text = level.label(),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            color = labelColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningIcon(level: ReasoningLevel) {
    when (level) {
        ReasoningLevel.OFF -> Icon(HugeIcons.Idea, null)
        ReasoningLevel.AUTO -> Icon(HugeIcons.Idea01, null)
        ReasoningLevel.LOW -> Icon(ReasoningLow, null)
        ReasoningLevel.MEDIUM -> Icon(ReasoningMedium, null)
        ReasoningLevel.HIGH -> Icon(ReasoningHigh, null)
        ReasoningLevel.XHIGH -> Icon(ReasoningHigh, null)
    }
}

@Composable
private fun ReasoningLevel.label(): String = when (this) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off)
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto)
    ReasoningLevel.LOW -> stringResource(R.string.reasoning_light)
    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium)
    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy)
    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh)
}

@Composable
@Preview(showBackground = true)
private fun ReasoningPickerPreview() {
    MaterialTheme {
        var level by remember { mutableStateOf(ReasoningLevel.AUTO) }
        ReasoningPicker(
            reasoningLevel = level,
            onUpdateReasoningLevel = { level = it },
        )
    }
}
