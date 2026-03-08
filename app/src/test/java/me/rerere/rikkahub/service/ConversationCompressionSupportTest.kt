package me.rerere.rikkahub.service

import java.io.File
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isCompressionCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompressionSupportTest {
    @Test
    fun `toCompressionTranscript should retain tool and document context`() {
        val tempFile = File.createTempFile("compression-support", ".txt").apply {
            writeText("Document body for compression support test.")
            deleteOnExit()
        }
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Main reply"),
                UIMessagePart.Reasoning("Reasoning details"),
                UIMessagePart.Document(
                    url = "file://${tempFile.absolutePath}",
                    fileName = tempFile.name,
                    mime = "text/plain"
                ),
                UIMessagePart.Tool(
                    toolCallId = "tool-1",
                    toolName = "shell",
                    input = "{\"cmd\":\"pwd\"}",
                    output = listOf(UIMessagePart.Text("/root/haqimi"))
                )
            )
        )

        val transcript = message.toCompressionTranscript()

        assertTrue(transcript.contains("[ASSISTANT]"))
        assertTrue(transcript.contains("Main reply"))
        assertTrue(transcript.contains("[Reasoning]"))
        assertTrue(transcript.contains("[Document]"))
        assertTrue(transcript.contains(tempFile.name))
        assertTrue(transcript.contains("[Tool] shell"))
        assertTrue(transcript.contains("/root/haqimi"))
    }

    @Test
    fun `createCompressionCheckpointMessage should mark checkpoint transcript`() {
        val message = createCompressionCheckpointMessage(
            summary = "Earlier messages were summarized here.",
            level = 2,
            sourceMessageCount = 18
        )

        val metadata = message.compressionCheckpointMetadata()
        val transcript = message.toCompressionTranscript()

        assertEquals(2, metadata?.level)
        assertEquals(18, metadata?.sourceMessageCount)
        assertTrue(message.isCompressionCheckpoint())
        assertTrue(transcript.contains("[COMPRESSION CHECKPOINT]"))
        assertTrue(transcript.contains("Earlier messages were summarized here."))
        assertTrue(transcript.contains("18 messages"))
    }

    @Test
    fun `normalizeCompressionCheckpointMessage should upgrade legacy metadata-only checkpoint`() {
        val legacy = UIMessage.user("Old checkpoint").copy(
            parts = listOf(
                UIMessagePart.Text(
                    text = "Old checkpoint",
                    metadata = kotlinx.serialization.json.buildJsonObject {
                        put("rikkahub.compression_checkpoint", kotlinx.serialization.json.JsonPrimitive(true))
                        put("rikkahub.compression_checkpoint_level", kotlinx.serialization.json.JsonPrimitive(3))
                        put(
                            "rikkahub.compression_checkpoint_source_message_count",
                            kotlinx.serialization.json.JsonPrimitive(21)
                        )
                    }
                )
            )
        )

        val normalized = normalizeCompressionCheckpointMessage(legacy)

        assertTrue(normalized.isCompressionCheckpoint())
        assertEquals(3, normalized.compressionCheckpointMetadata()?.level)
        assertEquals(21, normalized.compressionCheckpointMetadata()?.sourceMessageCount)
    }

    @Test
    fun `chunkCompressionEntries should split when entry count exceeds hard limit`() {
        val entries = List(300) { index -> "entry-$index" }

        val chunks = chunkCompressionEntries(entries, targetTokens = 2_000)

        assertEquals(2, chunks.size)
        assertEquals(256, chunks.first().size)
        assertEquals(44, chunks.last().size)
    }

    @Test
    fun `chunkCompressionEntries should split when token budget is exceeded`() {
        val longEntry = "Large compression block. ".repeat(1_200)
        val entries = List(3) { longEntry }

        val chunks = chunkCompressionEntries(entries, targetTokens = 500)

        assertTrue(chunks.size >= 2)
        assertEquals(3, chunks.sumOf { it.size })
    }

    @Test
    fun `splitMessagesForCompression should keep configured tail without budget`() {
        val messages = List(6) { index ->
            if (index % 2 == 0) UIMessage.user("message-$index") else UIMessage.assistant("message-$index")
        }

        val split = splitMessagesForCompression(
            messages = messages,
            keepRecentMessages = 3,
            targetTokens = 600,
        )

        assertEquals(3, split.messagesToCompress.size)
        assertEquals(3, split.messagesToKeep.size)
        assertEquals("message-5", split.messagesToKeep.last().toText())
    }

    @Test
    fun `splitMessagesForCompression should shrink tail to fit budget`() {
        val longText = "Token heavy message. ".repeat(220)
        val messages = List(6) { index ->
            if (index % 2 == 0) UIMessage.user("$index:$longText") else UIMessage.assistant("$index:$longText")
        }

        val split = splitMessagesForCompression(
            messages = messages,
            keepRecentMessages = 4,
            targetTokens = 500,
            maxInputTokensAfterCompression = 2_200,
        )

        assertTrue(split.messagesToCompress.isNotEmpty())
        assertEquals(1, split.messagesToKeep.size)
        val estimatedTotal = estimateConversationInputTokensWithoutReuse(split.messagesToKeep) +
            estimateCompressionCheckpointTokenReserve(500)
        assertTrue(estimatedTotal <= 2_200)
    }

    @Test
    fun `planConversationCompression should keep visible tail and absorb replacement history`() {
        val replacementHistory = listOf(
            createCompressionCheckpointMessage(
                summary = "Earlier summary",
                level = 2,
                sourceMessageCount = 16
            )
        )
        val visibleMessages = listOf(
            UIMessage.user("u1"),
            UIMessage.assistant("a1"),
            UIMessage.user("u2"),
            UIMessage.assistant("a2")
        )

        val plan = planConversationCompression(
            replacementHistoryMessages = replacementHistory,
            visibleMessages = visibleMessages,
            keepRecentMessages = 2,
            targetTokens = 600,
        )

        assertEquals(3, plan.messagesToCompress.size)
        assertEquals("Earlier summary", plan.messagesToCompress.first().toText())
        assertEquals(2, plan.visibleKeepCount)
        assertEquals("u2", plan.visibleMessagesToKeep.first().toText())
    }

    @Test
    fun `planConversationCompression should shrink visible tail without dropping replacement history`() {
        val replacementHistory = listOf(
            createCompressionCheckpointMessage(
                summary = "Long earlier summary",
                level = 3,
                sourceMessageCount = 24
            )
        )
        val longText = "Token heavy message. ".repeat(220)
        val visibleMessages = List(5) { index ->
            if (index % 2 == 0) UIMessage.user("$index:$longText") else UIMessage.assistant("$index:$longText")
        }

        val plan = planConversationCompression(
            replacementHistoryMessages = replacementHistory,
            visibleMessages = visibleMessages,
            keepRecentMessages = 4,
            targetTokens = 500,
            maxInputTokensAfterCompression = 2_200,
        )

        assertTrue(plan.messagesToCompress.first().isCompressionCheckpoint())
        assertEquals(1, plan.visibleKeepCount)
        val estimatedTotal = estimateConversationInputTokensWithoutReuse(plan.visibleMessagesToKeep) +
            estimateCompressionCheckpointTokenReserve(500)
        assertTrue(estimatedTotal <= 2_200)
    }
}
