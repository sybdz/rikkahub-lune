package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

private const val MAX_COMPRESSION_REVISIONS = 16

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val replacementHistory: List<ConversationCheckpoint> = emptyList(),
    val compressionRevisions: List<ConversationCompressionRevision> = emptyList(),
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    val replacementHistoryMessages: List<UIMessage>
        get() = replacementHistory.map(ConversationCheckpoint::message)

    val compressionRevisionCount: Int
        get() = compressionRevisions.size

    val fullContextMessages: List<UIMessage>
        get() = replacementHistoryMessages + currentMessages

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun buildGenerationMessages(messageRange: IntRange? = null): List<UIMessage> {
        return replacementHistoryMessages + currentMessages.selectMessages(messageRange)
    }

    fun recordCompressionRevision(
        reason: CompressionRevisionReason,
        previousCheckpoints: List<ConversationCheckpoint>,
        nextCheckpoints: List<ConversationCheckpoint>,
        compressedVisibleMessageCount: Int,
        keptVisibleMessageCount: Int,
    ): Conversation {
        return copy(
            compressionRevisions = (compressionRevisions + ConversationCompressionRevision(
                reason = reason,
                previousCheckpoints = previousCheckpoints,
                nextCheckpoints = nextCheckpoints,
                compressedVisibleMessageCount = compressedVisibleMessageCount.coerceAtLeast(0),
                keptVisibleMessageCount = keptVisibleMessageCount.coerceAtLeast(0),
            )).takeLast(MAX_COMPRESSION_REVISIONS)
        )
    }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun referencesReplacementHistoryNode(nodeId: Uuid?): Boolean {
        return nodeId != null && replacementHistory.any { checkpoint -> checkpoint.id == nodeId }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        return updateCurrentMessages(startIndex = 0, messages = messages)
    }

    fun updateCurrentMessages(startIndex: Int, messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val nodeIndex = startIndex + index
            val node = newNodes
                .getOrElse(nodeIndex) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (nodeIndex > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[nodeIndex] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

@Serializable
data class ConversationCheckpoint(
    val id: Uuid = Uuid.random(),
    val message: UIMessage,
)

@Serializable
enum class CompressionRevisionReason {
    MANUAL,
    AUTO_TRIGGER,
    RANGE_REGENERATE,
}

@Serializable
data class ConversationCompressionRevision(
    val id: Uuid = Uuid.random(),
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    val reason: CompressionRevisionReason,
    val previousCheckpoints: List<ConversationCheckpoint> = emptyList(),
    val nextCheckpoints: List<ConversationCheckpoint> = emptyList(),
    val compressedVisibleMessageCount: Int = 0,
    val keptVisibleMessageCount: Int = 0,
)

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

private fun List<UIMessage>.selectMessages(messageRange: IntRange?): List<UIMessage> {
    return if (messageRange == null) {
        this
    } else {
        subList(messageRange.first, messageRange.last + 1)
    }
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
