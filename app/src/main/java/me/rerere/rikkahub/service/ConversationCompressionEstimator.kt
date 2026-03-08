package me.rerere.rikkahub.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.createDocumentPromptText
import me.rerere.rikkahub.data.ai.transformers.readDocumentContent

private const val REQUEST_OVERHEAD_TOKENS = 24
private const val MESSAGE_OVERHEAD_TOKENS = 6
private const val MEDIA_PART_ESTIMATE_TOKENS = 32
private const val DOCUMENT_PART_ESTIMATE_TOKENS = 48
private const val TOOL_PART_ESTIMATE_TOKENS = 24
private const val UTF8_BYTES_PER_TOKEN = 3.5

internal suspend fun estimateConversationInputTokens(messages: List<UIMessage>): Int {
    return estimateConversationInputTokens(messages, allowPromptTokenReuse = true)
}

internal suspend fun estimateConversationInputTokens(
    messages: List<UIMessage>,
    allowPromptTokenReuse: Boolean,
): Int = withContext(Dispatchers.IO) {
    if (messages.isEmpty()) {
        0
    } else {
        val lastAssistantIndex = messages.indexOfLast { message ->
            message.role == MessageRole.ASSISTANT && (message.usage?.promptTokens ?: 0) > 0
        }

        if (allowPromptTokenReuse && lastAssistantIndex >= 0) {
            val exactPromptTokens = messages[lastAssistantIndex].usage?.promptTokens ?: 0
            exactPromptTokens + messages.drop(lastAssistantIndex).sumOf(::estimateMessageTokens) + REQUEST_OVERHEAD_TOKENS
        } else {
            estimateConversationInputTokensWithoutReuse(messages)
        }
    }
}

internal fun estimateConversationInputTokensWithoutReuse(messages: List<UIMessage>): Int {
    if (messages.isEmpty()) return 0
    return messages.sumOf(::estimateMessageTokens) + REQUEST_OVERHEAD_TOKENS
}

internal fun estimateMessageTokens(message: UIMessage): Int {
    return MESSAGE_OVERHEAD_TOKENS + message.parts.sumOf(::estimatePartTokens)
}

@Suppress("DEPRECATION")
internal fun estimatePartTokens(part: UIMessagePart): Int {
    return when (part) {
        is UIMessagePart.Text -> estimateTextTokens(part.text)
        is UIMessagePart.Image -> MEDIA_PART_ESTIMATE_TOKENS
        is UIMessagePart.Video -> MEDIA_PART_ESTIMATE_TOKENS
        is UIMessagePart.Audio -> MEDIA_PART_ESTIMATE_TOKENS
        is UIMessagePart.Document -> estimateDocumentTokens(part, readDocumentContent(part))
        is UIMessagePart.Reasoning -> 0
        is UIMessagePart.Search -> 0
        is UIMessagePart.Tool -> {
            TOOL_PART_ESTIMATE_TOKENS +
                estimateTextTokens(part.toolName) +
                estimateTextTokens(part.input) +
                part.output.sumOf(::estimatePartTokens)
        }

        is UIMessagePart.ToolCall -> {
            TOOL_PART_ESTIMATE_TOKENS +
                estimateTextTokens(part.toolName) +
                estimateTextTokens(part.arguments)
        }

        is UIMessagePart.ToolResult -> {
            TOOL_PART_ESTIMATE_TOKENS +
                estimateTextTokens(part.toolName) +
                estimateTextTokens(part.arguments.toString()) +
                estimateTextTokens(part.content.toString())
        }
    }
}

internal fun estimateDocumentTokens(
    document: UIMessagePart.Document,
    documentContent: String,
): Int {
    return DOCUMENT_PART_ESTIMATE_TOKENS +
        estimateTextTokens(document.fileName) +
        estimateTextTokens(
            createDocumentPromptText(
                document = document,
                content = documentContent
            )
        )
}

internal fun estimateTextTokens(text: String): Int {
    if (text.isBlank()) return 0
    val utf8Bytes = text.toByteArray(Charsets.UTF_8).size.toDouble()
    return ceil(utf8Bytes / UTF8_BYTES_PER_TOKEN).toInt().coerceAtLeast(1)
}
