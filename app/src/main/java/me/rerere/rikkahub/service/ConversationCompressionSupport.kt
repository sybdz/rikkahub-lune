package me.rerere.rikkahub.service

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UISyntheticKind
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.readDocumentContent
import kotlin.math.max

private const val COMPRESSION_MIN_CHUNK_INPUT_TOKENS = 4_000
private const val COMPRESSION_CHUNK_TOKEN_MULTIPLIER = 8
private const val COMPRESSION_MAX_ENTRIES_PER_CHUNK = 256
private const val COMPRESSION_TEXT_PART_MAX_CHARS = 8_000
private const val COMPRESSION_REASONING_PART_MAX_CHARS = 3_000
private const val COMPRESSION_DOCUMENT_PART_MAX_CHARS = 12_000
private const val COMPRESSION_TOOL_INPUT_MAX_CHARS = 4_000
private const val COMPRESSION_TOOL_OUTPUT_MAX_CHARS = 6_000
private const val COMPRESSION_CHECKPOINT_METADATA_KEY = "rikkahub.compression_checkpoint"
private const val COMPRESSION_CHECKPOINT_LEVEL_METADATA_KEY = "rikkahub.compression_checkpoint_level"
private const val COMPRESSION_CHECKPOINT_SOURCE_MESSAGE_COUNT_METADATA_KEY =
    "rikkahub.compression_checkpoint_source_message_count"
private const val COMPRESSION_CHECKPOINT_BUDGET_BUFFER_MIN_TOKENS = 96

internal data class CompressionMessageSplit(
    val messagesToCompress: List<UIMessage>,
    val messagesToKeep: List<UIMessage>,
)

internal data class ConversationCompressionPlan(
    val messagesToCompress: List<UIMessage>,
    val visibleMessagesToKeep: List<UIMessage>,
) {
    val visibleKeepCount: Int
        get() = visibleMessagesToKeep.size
}

internal data class CompressionCheckpointMetadata(
    val level: Int,
    val sourceMessageCount: Int,
)

internal fun createCompressionCheckpointMessage(
    summary: String,
    level: Int,
    sourceMessageCount: Int,
): UIMessage {
    val normalizedLevel = level.coerceAtLeast(1)
    val normalizedSourceMessageCount = sourceMessageCount.coerceAtLeast(0)
    return UIMessage(
        role = me.rerere.ai.core.MessageRole.USER,
        syntheticKind = UISyntheticKind.CompressionCheckpoint(
            level = normalizedLevel,
            sourceMessageCount = normalizedSourceMessageCount,
        ),
        parts = listOf(
            UIMessagePart.Text(
                text = summary,
                metadata = buildJsonObject {
                    put(COMPRESSION_CHECKPOINT_METADATA_KEY, JsonPrimitive(true))
                    put(COMPRESSION_CHECKPOINT_LEVEL_METADATA_KEY, JsonPrimitive(normalizedLevel))
                    put(
                        COMPRESSION_CHECKPOINT_SOURCE_MESSAGE_COUNT_METADATA_KEY,
                        JsonPrimitive(normalizedSourceMessageCount)
                    )
                }
            )
        )
    )
}

internal fun normalizeCompressionCheckpointMessage(message: UIMessage): UIMessage {
    if (message.syntheticKind != null) return message
    val metadata = message.compressionCheckpointMetadata() ?: return message
    return message.copy(
        syntheticKind = UISyntheticKind.CompressionCheckpoint(
            level = metadata.level,
            sourceMessageCount = metadata.sourceMessageCount,
        )
    )
}

internal fun UIMessage.compressionCheckpointMetadata(): CompressionCheckpointMetadata? {
    when (val kind = syntheticKind) {
        is UISyntheticKind.CompressionCheckpoint -> {
            return CompressionCheckpointMetadata(
                level = kind.level.coerceAtLeast(1),
                sourceMessageCount = kind.sourceMessageCount.coerceAtLeast(0),
            )
        }
        null -> {}
    }

    val textPart = parts.filterIsInstance<UIMessagePart.Text>().firstOrNull() ?: return null
    val metadata = textPart.metadata ?: return null
    val isCheckpoint = metadata[COMPRESSION_CHECKPOINT_METADATA_KEY]
        ?.jsonPrimitive
        ?.booleanOrNull == true
    if (!isCheckpoint) return null

    return CompressionCheckpointMetadata(
        level = metadata[COMPRESSION_CHECKPOINT_LEVEL_METADATA_KEY]
            ?.jsonPrimitive
            ?.intOrNull
            ?.coerceAtLeast(1)
            ?: 1,
        sourceMessageCount = metadata[COMPRESSION_CHECKPOINT_SOURCE_MESSAGE_COUNT_METADATA_KEY]
            ?.jsonPrimitive
            ?.intOrNull
            ?.coerceAtLeast(0)
            ?: 0,
    )
}

internal fun splitMessagesForCompression(
    messages: List<UIMessage>,
    keepRecentMessages: Int,
    targetTokens: Int,
    maxInputTokensAfterCompression: Int? = null,
): CompressionMessageSplit {
    if (keepRecentMessages <= 0) {
        return CompressionMessageSplit(
            messagesToCompress = messages,
            messagesToKeep = emptyList(),
        )
    }

    val maxKeepCount = keepRecentMessages.coerceAtMost((messages.size - 1).coerceAtLeast(0))
    if (maxKeepCount <= 0) {
        return CompressionMessageSplit(
            messagesToCompress = emptyList(),
            messagesToKeep = messages,
        )
    }

    if (maxInputTokensAfterCompression == null || maxInputTokensAfterCompression <= 0) {
        val messagesToKeep = messages.takeLast(maxKeepCount)
        return CompressionMessageSplit(
            messagesToCompress = messages.dropLast(messagesToKeep.size),
            messagesToKeep = messagesToKeep,
        )
    }

    val checkpointTokenReserve = estimateCompressionCheckpointTokenReserve(targetTokens)
    for (candidateKeepCount in maxKeepCount downTo 1) {
        val messagesToKeep = messages.takeLast(candidateKeepCount)
        val estimatedTotalTokens = estimateConversationInputTokensWithoutReuse(messagesToKeep) +
            checkpointTokenReserve
        if (estimatedTotalTokens <= maxInputTokensAfterCompression) {
            return CompressionMessageSplit(
                messagesToCompress = messages.dropLast(messagesToKeep.size),
                messagesToKeep = messagesToKeep,
            )
        }
    }

    val fallbackMessagesToKeep = messages.takeLast(1)
    return CompressionMessageSplit(
        messagesToCompress = messages.dropLast(fallbackMessagesToKeep.size),
        messagesToKeep = fallbackMessagesToKeep,
    )
}

internal fun planConversationCompression(
    replacementHistoryMessages: List<UIMessage>,
    visibleMessages: List<UIMessage>,
    keepRecentMessages: Int,
    targetTokens: Int,
    maxInputTokensAfterCompression: Int? = null,
): ConversationCompressionPlan {
    val preferredKeepCount = keepRecentMessages
        .coerceAtLeast(0)
        .coerceAtMost(visibleMessages.size)
    val minKeepCount = if (visibleMessages.isNotEmpty()) 1 else 0

    if (maxInputTokensAfterCompression == null || maxInputTokensAfterCompression <= 0) {
        return buildConversationCompressionPlan(
            replacementHistoryMessages = replacementHistoryMessages,
            visibleMessages = visibleMessages,
            visibleKeepCount = preferredKeepCount,
        )
    }

    val checkpointTokenReserve = estimateCompressionCheckpointTokenReserve(targetTokens)
    for (candidateKeepCount in preferredKeepCount downTo minKeepCount) {
        val plan = buildConversationCompressionPlan(
            replacementHistoryMessages = replacementHistoryMessages,
            visibleMessages = visibleMessages,
            visibleKeepCount = candidateKeepCount,
        )
        if (plan.messagesToCompress.isEmpty()) {
            continue
        }

        val estimatedTotalTokens = estimateConversationInputTokensWithoutReuse(plan.visibleMessagesToKeep) +
            checkpointTokenReserve
        if (estimatedTotalTokens <= maxInputTokensAfterCompression) {
            return plan
        }
    }

    return buildConversationCompressionPlan(
        replacementHistoryMessages = replacementHistoryMessages,
        visibleMessages = visibleMessages,
        visibleKeepCount = minKeepCount,
    )
}

internal fun estimateCompressionCheckpointTokenReserve(targetTokens: Int): Int {
    val normalizedTarget = targetTokens.coerceAtLeast(1)
    return normalizedTarget + max(
        COMPRESSION_CHECKPOINT_BUDGET_BUFFER_MIN_TOKENS,
        normalizedTarget / 4
    )
}

internal fun UIMessage.toCompressionTranscript(): String {
    val checkpointMetadata = compressionCheckpointMetadata()
    val renderedParts = parts.mapNotNull { part ->
        part.toCompressionTranscriptPart()
    }
    return buildString {
        append("[")
        append(role.name)
        append("]")
        checkpointMetadata?.let { metadata ->
            append(" [COMPRESSION CHECKPOINT]")
            append("\n")
            append("Compressed earlier context at level ")
            append(metadata.level)
            if (metadata.sourceMessageCount > 0) {
                append(" from ")
                append(metadata.sourceMessageCount)
                append(" messages")
            }
        }
        if (renderedParts.isNotEmpty()) {
            append("\n")
            append(renderedParts.joinToString(separator = "\n"))
        }
    }
}

internal fun UIMessage.effectiveCompressionSourceMessageCount(): Int {
    return compressionCheckpointMetadata()
        ?.sourceMessageCount
        ?.takeIf { it > 0 }
        ?: 1
}

internal fun UIMessage.effectiveCompressionLevel(): Int {
    return compressionCheckpointMetadata()
        ?.level
        ?.takeIf { it > 0 }
        ?: 0
}

private fun buildConversationCompressionPlan(
    replacementHistoryMessages: List<UIMessage>,
    visibleMessages: List<UIMessage>,
    visibleKeepCount: Int,
): ConversationCompressionPlan {
    val normalizedKeepCount = visibleKeepCount.coerceIn(0, visibleMessages.size)
    val visibleMessagesToKeep = visibleMessages.takeLast(normalizedKeepCount)
    val visibleMessagesToCompress = visibleMessages.take(
        (visibleMessages.size - normalizedKeepCount).coerceAtLeast(0)
    )
    return ConversationCompressionPlan(
        messagesToCompress = replacementHistoryMessages + visibleMessagesToCompress,
        visibleMessagesToKeep = visibleMessagesToKeep,
    )
}

internal fun chunkCompressionEntries(
    entries: List<String>,
    targetTokens: Int,
): List<List<String>> {
    if (entries.isEmpty()) return emptyList()

    val chunkInputTokenBudget = (targetTokens * COMPRESSION_CHUNK_TOKEN_MULTIPLIER)
        .coerceAtLeast(COMPRESSION_MIN_CHUNK_INPUT_TOKENS)

    val chunks = mutableListOf<MutableList<String>>()
    var currentChunk = mutableListOf<String>()
    var currentChunkTokens = 0

    entries.forEach { entry ->
        val entryTokens = estimateTextTokens(entry)
        val exceedsChunkBudget = currentChunkTokens > 0 &&
            currentChunkTokens + entryTokens > chunkInputTokenBudget
        val exceedsChunkCount = currentChunk.size >= COMPRESSION_MAX_ENTRIES_PER_CHUNK

        if (exceedsChunkBudget || exceedsChunkCount) {
            chunks += currentChunk
            currentChunk = mutableListOf()
            currentChunkTokens = 0
        }

        currentChunk += entry
        currentChunkTokens += entryTokens
    }

    if (currentChunk.isNotEmpty()) {
        chunks += currentChunk
    }

    return chunks
}

@Suppress("DEPRECATION")
private fun UIMessagePart.toCompressionTranscriptPart(): String? {
    return when (this) {
        is UIMessagePart.Text -> truncateForCompression(text, COMPRESSION_TEXT_PART_MAX_CHARS)
            .takeIf { it.isNotBlank() }

        is UIMessagePart.Image -> "[Image attachment] ${describeAttachment(url)}"
        is UIMessagePart.Video -> "[Video attachment] ${describeAttachment(url)}"
        is UIMessagePart.Audio -> "[Audio attachment] ${describeAttachment(url)}"
        is UIMessagePart.Document -> buildString {
            append("[Document] ")
            append(fileName)
            append(" (")
            append(mime)
            append(")")
            val content = truncateForCompression(
                readDocumentContent(this@toCompressionTranscriptPart),
                COMPRESSION_DOCUMENT_PART_MAX_CHARS
            )
            if (content.isNotBlank()) {
                append("\n")
                append(content)
            }
        }

        is UIMessagePart.Reasoning -> truncateForCompression(
            reasoning,
            COMPRESSION_REASONING_PART_MAX_CHARS
        ).takeIf { it.isNotBlank() }?.let { "[Reasoning]\n$it" }

        is UIMessagePart.Search -> "[Search tool used]"

        is UIMessagePart.ToolCall -> buildString {
            append("[Tool call] ")
            append(toolName.ifBlank { "unknown_tool" })
            val arguments = truncateForCompression(arguments, COMPRESSION_TOOL_INPUT_MAX_CHARS)
            if (arguments.isNotBlank()) {
                append("\nInput:\n")
                append(arguments)
            }
        }

        is UIMessagePart.ToolResult -> buildString {
            append("[Tool result] ")
            append(toolName.ifBlank { "unknown_tool" })
            val arguments = truncateForCompression(arguments.toString(), COMPRESSION_TOOL_INPUT_MAX_CHARS)
            val content = truncateForCompression(content.toString(), COMPRESSION_TOOL_OUTPUT_MAX_CHARS)
            if (arguments.isNotBlank()) {
                append("\nInput:\n")
                append(arguments)
            }
            if (content.isNotBlank()) {
                append("\nOutput:\n")
                append(content)
            }
        }

        is UIMessagePart.Tool -> buildString {
            append("[Tool] ")
            append(toolName.ifBlank { "unknown_tool" })
            val inputText = truncateForCompression(input, COMPRESSION_TOOL_INPUT_MAX_CHARS)
            if (inputText.isNotBlank()) {
                append("\nInput:\n")
                append(inputText)
            }
            val outputText = output.mapNotNull { outputPart ->
                outputPart.toCompressionTranscriptPart()
            }.joinToString(separator = "\n")
            val truncatedOutput = truncateForCompression(outputText, COMPRESSION_TOOL_OUTPUT_MAX_CHARS)
            if (truncatedOutput.isNotBlank()) {
                append("\nOutput:\n")
                append(truncatedOutput)
            }
        }
    }
}

private fun truncateForCompression(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    return text.take(maxChars) + "\n...[truncated]"
}

private fun describeAttachment(rawUrl: String): String {
    val uri = runCatching { rawUrl.toUri() }.getOrNull()
    return when {
        uri == null -> rawUrl
        uri.scheme == "file" -> uri.lastPathSegment ?: rawUrl
        uri.scheme.isNullOrBlank() -> rawUrl
        else -> uri.lastPathSegment ?: uri.toString()
    }
}

private val Uri.lastPathSegment: String?
    get() = runCatching { pathSegments.lastOrNull() }.getOrNull()
