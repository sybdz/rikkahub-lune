package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.service.createCompressionCheckpointMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    @Test
    fun `buildGenerationMessages should prepend replacement history to visible selection`() {
        val checkpoint = ConversationCheckpoint(
            message = createCompressionCheckpointMessage(
                summary = "Earlier context",
                level = 2,
                sourceMessageCount = 12
            )
        )
        val conversation = Conversation.ofId(
            id = kotlin.uuid.Uuid.random(),
            messages = listOf(
                UIMessage.user("u1").toMessageNode(),
                UIMessage.assistant("a1").toMessageNode(),
                UIMessage.user("u2").toMessageNode()
            )
        ).copy(
            replacementHistory = listOf(checkpoint)
        )

        val generationMessages = conversation.buildGenerationMessages(1..<3)

        assertEquals(3, generationMessages.size)
        assertEquals("Earlier context", generationMessages[0].toText())
        assertEquals("a1", generationMessages[1].toText())
        assertEquals("u2", generationMessages[2].toText())
    }

    @Test
    fun `updateCurrentMessages should respect start index when writing visible nodes`() {
        val originalAssistant = UIMessage.assistant("a1")
        val conversation = Conversation.ofId(
            id = kotlin.uuid.Uuid.random(),
            messages = listOf(
                UIMessage.user("u1").toMessageNode(),
                originalAssistant.toMessageNode(),
                UIMessage.user("u2").toMessageNode()
            )
        )

        val regeneratedAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = originalAssistant.parts
        )
        val appendedAssistant = UIMessage.assistant("a2")
        val updatedConversation = conversation.updateCurrentMessages(
            startIndex = 1,
            messages = listOf(regeneratedAssistant, appendedAssistant)
        )

        assertEquals("u1", updatedConversation.currentMessages[0].toText())
        assertEquals("a1", updatedConversation.currentMessages[1].toText())
        assertEquals("a2", updatedConversation.currentMessages[2].toText())
        assertEquals(2, updatedConversation.messageNodes[1].messages.size)
    }

    @Test
    fun `recordCompressionRevision should append revision history and keep latest entries`() {
        var conversation = Conversation.ofId(
            id = kotlin.uuid.Uuid.random(),
            messages = listOf(UIMessage.user("u1").toMessageNode())
        )

        repeat(20) { index ->
            conversation = conversation.recordCompressionRevision(
                reason = if (index == 0) CompressionRevisionReason.MANUAL else CompressionRevisionReason.AUTO_TRIGGER,
                previousCheckpoints = listOf(
                    ConversationCheckpoint(
                        message = createCompressionCheckpointMessage(
                            summary = "prev-$index",
                            level = 1,
                            sourceMessageCount = index + 1
                        )
                    )
                ),
                nextCheckpoints = listOf(
                    ConversationCheckpoint(
                        message = createCompressionCheckpointMessage(
                            summary = "next-$index",
                            level = 2,
                            sourceMessageCount = index + 2
                        )
                    )
                ),
                compressedVisibleMessageCount = index + 3,
                keptVisibleMessageCount = 2,
            )
        }

        assertEquals(16, conversation.compressionRevisionCount)
        assertEquals("prev-4", conversation.compressionRevisions.first().previousCheckpoints.single().message.toText())
        assertEquals("next-19", conversation.compressionRevisions.last().nextCheckpoints.single().message.toText())
        assertEquals(CompressionRevisionReason.AUTO_TRIGGER, conversation.compressionRevisions.last().reason)
        assertEquals(22, conversation.compressionRevisions.last().compressedVisibleMessageCount)
        assertEquals(2, conversation.compressionRevisions.last().keptVisibleMessageCount)
    }

    @Test
    fun `referencesReplacementHistoryNode should resolve legacy checkpoint ids`() {
        val legacyNodeId = kotlin.uuid.Uuid.random()
        val conversation = Conversation.ofId(
            id = kotlin.uuid.Uuid.random(),
            messages = listOf(UIMessage.user("u1").toMessageNode())
        ).copy(
            replacementHistory = listOf(
                ConversationCheckpoint(
                    id = legacyNodeId,
                    message = createCompressionCheckpointMessage(
                        summary = "Earlier context",
                        level = 2,
                        sourceMessageCount = 12
                    )
                )
            )
        )

        assertTrue(conversation.referencesReplacementHistoryNode(legacyNodeId))
        assertFalse(conversation.referencesReplacementHistoryNode(kotlin.uuid.Uuid.random()))
        assertFalse(conversation.referencesReplacementHistoryNode(null))
    }
}
