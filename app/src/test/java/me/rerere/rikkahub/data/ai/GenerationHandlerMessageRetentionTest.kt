package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerMessageRetentionTest {

    @Test
    fun `prepareMessagesForGeneration should preserve current tool chain while pruning earlier rounds`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("First turn"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("History before tool"),
                    UIMessagePart.Tool(
                        toolCallId = "history-call",
                        toolName = "search",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("history result"))
                    ),
                    UIMessagePart.Text("History after tool")
                )
            ),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Current turn"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Current before tool"),
                    UIMessagePart.Tool(
                        toolCallId = "current-call",
                        toolName = "search",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("current result"))
                    ),
                    UIMessagePart.Text("Current after tool")
                )
            )
        )

        val result = messages.prepareMessagesForGeneration(
            contextMessageSize = 4,
            toolCallKeepRoundsLimit = 0,
        )

        assertEquals(4, result.size)
        assertFalse(result[1].getTools().any { it.toolCallId == "history-call" })
        assertTrue(result[3].getTools().any { it.toolCallId == "current-call" })
    }

    @Test
    fun `prepareMessagesForGeneration should backtrack context before pruning tool rounds`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("First turn"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Before tool"),
                    UIMessagePart.Tool(
                        toolCallId = "history-call",
                        toolName = "search",
                        input = "{}",
                        output = listOf(UIMessagePart.Text("history result"))
                    ),
                    UIMessagePart.Text("After tool")
                )
            ),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Second turn"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Latest response")))
        )

        val result = messages.prepareMessagesForGeneration(
            contextMessageSize = 3,
            toolCallKeepRoundsLimit = 0,
        )

        assertEquals(4, result.size)
        assertEquals(MessageRole.USER, result.first().role)
        assertEquals("First turn", result.first().toText())
        assertTrue(result[1].parts.filterIsInstance<UIMessagePart.Text>().isNotEmpty())
        assertFalse(result[1].getTools().any { it.toolCallId == "history-call" })
    }
}
