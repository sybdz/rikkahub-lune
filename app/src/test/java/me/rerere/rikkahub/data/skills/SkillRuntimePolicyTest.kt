package me.rerere.rikkahub.data.skills

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRuntimePolicyTest {
    @Test
    fun `resolveSkillToolPolicy should keep unrestricted when no activations declare allowed tools`() {
        val policy = resolveSkillToolPolicy(
            listOf(
                SkillActivationEntry(
                    entry = SkillCatalogEntry(
                        directoryName = "demo",
                        path = "/skills/demo",
                        name = "demo",
                        description = "No restrictions",
                    ),
                    markdown = "",
                    resourceFiles = emptyList(),
                )
            )
        )

        val visibleTools = policy.filterVisibleTools(
            listOf(
                stubTool("termux_exec"),
                stubTool("get_time_info"),
            )
        )

        assertEquals(listOf("termux_exec", "get_time_info"), visibleTools.map { it.name })
        assertNull(
            policy.validate(
                toolName = "termux_exec",
                input = buildJsonObject { put("command", JsonPrimitive("pwd")) },
            )
        )
    }

    @Test
    fun `resolveSkillToolPolicy should restrict visible tools for explicit activations`() {
        val policy = resolveSkillToolPolicy(
            listOf(
                skillActivation(directoryName = "time-only", allowedTools = "get_time_info")
            )
        )

        val visibleTools = policy.filterVisibleTools(
            listOf(
                stubTool("termux_exec"),
                stubTool("activate_skill"),
                stubTool("get_time_info"),
                stubTool("clipboard_tool"),
            )
        )

        assertEquals(listOf("activate_skill", "get_time_info"), visibleTools.map { it.name })
        assertTrue(
            policy.validate(
                toolName = "termux_exec",
                input = buildJsonObject { put("command", JsonPrimitive("pwd")) },
            ) != null
        )
        assertNull(
            policy.validate(
                toolName = "activate_skill",
                input = buildJsonObject { put("skill", JsonPrimitive("demo")) },
            )
        )
        assertTrue(
            policy.validate(
                toolName = "run_skill_script",
                input = buildJsonObject { put("script_path", JsonPrimitive("scripts/run.sh")) },
            ) != null
        )
    }

    @Test
    fun `resolveSkillToolPolicy should allow read only shell commands`() {
        val policy = resolveSkillToolPolicy(
            listOf(
                skillActivation(directoryName = "reader", allowedTools = "Read")
            )
        )

        assertNull(
            policy.validate(
                toolName = "termux_exec",
                input = buildJsonObject {
                    put("command", JsonPrimitive("rg TODO README.md"))
                },
            )
        )
        assertTrue(
            policy.validate(
                toolName = "write_stdin",
                input = buildJsonObject { put("session_id", JsonPrimitive("1")) },
            ) != null
        )
    }

    @Test
    fun `resolveSkillToolPolicy should reject mutating or interactive shell commands in read only mode`() {
        val policy = resolveSkillToolPolicy(
            listOf(
                skillActivation(directoryName = "reader", allowedTools = "Read")
            )
        )

        assertTrue(
            policy.validate(
                toolName = "termux_exec",
                input = buildJsonObject {
                    put("command", JsonPrimitive("rm -rf build"))
                },
            ) != null
        )
        assertTrue(
            policy.validate(
                toolName = "termux_exec",
                input = buildJsonObject {
                    put("command", JsonPrimitive("tail -f log.txt"))
                    put("tty", JsonPrimitive(true))
                },
            ) != null
        )
    }

    @Test
    fun `resolveSkillToolPolicy should keep the most restrictive shell access across multiple skills`() {
        val policy = resolveSkillToolPolicy(
            listOf(
                skillActivation(directoryName = "runner", allowedTools = "Bash"),
                skillActivation(directoryName = "reader", allowedTools = "Read"),
            )
        )

        assertNull(
            policy.validate(
                toolName = "termux_exec",
                input = buildJsonObject {
                    put("command", JsonPrimitive("git status"))
                },
            )
        )
        assertTrue(
            policy.validate(
                toolName = "termux_exec",
                input = buildJsonObject {
                    put("command", JsonPrimitive("mkdir out"))
                },
            ) != null
        )
        assertTrue(
            policy.validate(
                toolName = "write_stdin",
                input = buildJsonObject { put("session_id", JsonPrimitive("42")) },
            ) != null
        )
        assertTrue(
            policy.validate(
                toolName = "run_skill_script",
                input = buildJsonObject { put("script_path", JsonPrimitive("scripts/run.sh")) },
            ) != null
        )
    }

    @Test
    fun `resolveSkillToolPolicy should allow run skill script when shell access is full`() {
        val policy = resolveSkillToolPolicy(
            listOf(
                skillActivation(directoryName = "runner", allowedTools = "Bash")
            )
        )

        assertNull(
            policy.validate(
                toolName = "run_skill_script",
                input = buildJsonObject { put("script_path", JsonPrimitive("scripts/run.sh")) },
            )
        )
    }

    @Test
    fun `isReadOnlyShellCommand should reject operators and allow safe git reads`() {
        assertTrue(isReadOnlyShellCommand("git diff -- app/src"))
        assertFalse(isReadOnlyShellCommand("git commit -m test"))
        assertFalse(isReadOnlyShellCommand("rg TODO . | head"))
        assertFalse(isReadOnlyShellCommand("cat README.md > /tmp/out"))
    }

    @Test
    fun `findUnknownSkillAllowedToolTokens should report unsupported syntax`() {
        assertEquals(
            listOf("Bash(gh", "*)"),
            findUnknownSkillAllowedToolTokens("Bash(gh *)"),
        )
        assertTrue(findUnknownSkillAllowedToolTokens("Bash Read").isEmpty())
    }

    private fun stubTool(name: String): Tool {
        return Tool(
            name = name,
            description = "",
            execute = { emptyList<UIMessagePart>() },
        )
    }

    private fun skillActivation(
        directoryName: String,
        allowedTools: String,
    ): SkillActivationEntry {
        return SkillActivationEntry(
            entry = SkillCatalogEntry(
                directoryName = directoryName,
                path = "/skills/$directoryName",
                name = directoryName,
                description = directoryName,
                allowedTools = allowedTools,
            ),
            markdown = "",
            resourceFiles = emptyList(),
        )
    }
}
