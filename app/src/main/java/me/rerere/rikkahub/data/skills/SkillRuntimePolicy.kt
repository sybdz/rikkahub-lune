package me.rerere.rikkahub.data.skills

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool

private val SkillAllowedToolSplitRegex = Regex("[,，\\s\\r\\n]+")
private val ReadOnlyShellOperatorsRegex = Regex("""(^|[^\\])(?:>>?|<<|[;&|])""")
private val GitReadOnlySubcommands = setOf(
    "status",
    "diff",
    "show",
    "log",
    "rev-parse",
    "branch",
    "remote",
    "ls-files",
    "grep",
    "blame",
    "describe",
)
private val ReadOnlyShellCommands = setOf(
    "cat",
    "head",
    "tail",
    "ls",
    "pwd",
    "stat",
    "file",
    "wc",
    "cut",
    "sort",
    "uniq",
    "basename",
    "dirname",
    "realpath",
    "readlink",
    "which",
    "type",
    "env",
    "printenv",
    "id",
    "whoami",
    "find",
    "grep",
    "rg",
    "tree",
)
private val FullShellToolNames = setOf(
    "termux_exec",
    "write_stdin",
    "list_pty_sessions",
    "close_pty_session",
)
private val ReadOnlyShellToolNames = setOf("termux_exec")

internal enum class SkillShellAccess(
    private val level: Int,
) {
    NONE(0),
    READ_ONLY(1),
    FULL(2),
    UNRESTRICTED(3),
    ;

    companion object {
        fun mostRestrictive(
            left: SkillShellAccess,
            right: SkillShellAccess,
        ): SkillShellAccess {
            return if (left.level <= right.level) left else right
        }

        fun leastRestrictive(
            left: SkillShellAccess,
            right: SkillShellAccess,
        ): SkillShellAccess {
            return if (left.level >= right.level) left else right
        }
    }
}

internal data class SkillToolPolicyViolation(
    val message: String,
)

internal data class SkillToolPolicy(
    val activeSkillDirectoryNames: List<String> = emptyList(),
    private val visibleToolNames: Set<String>? = null,
    private val shellAccess: SkillShellAccess = SkillShellAccess.UNRESTRICTED,
) {
    val isRestricted: Boolean = visibleToolNames != null

    fun filterVisibleTools(tools: List<Tool>): List<Tool> {
        val allowedToolNames = visibleToolNames ?: return tools
        return tools.filter { tool ->
            tool.name in allowedToolNames ||
                tool.name in AlwaysAllowedSkillRuntimeToolNames ||
                (tool.name == "run_skill_script" && canRunSkillScripts())
        }
    }

    fun validate(toolName: String, input: JsonElement): SkillToolPolicyViolation? {
        val allowedToolNames = visibleToolNames
        if (
            allowedToolNames != null &&
            toolName !in allowedToolNames &&
            toolName !in AlwaysAllowedSkillRuntimeToolNames &&
            !(toolName == "run_skill_script" && canRunSkillScripts())
        ) {
            return SkillToolPolicyViolation(
                message = "Tool $toolName is not allowed by the active skills: ${activeSkillDirectoryNames.joinToString(", ")}"
            )
        }

        return when (toolName) {
            "termux_exec" -> validateTermuxExec(input)
            "write_stdin", "list_pty_sessions", "close_pty_session" -> {
                if (shellAccess != SkillShellAccess.FULL && shellAccess != SkillShellAccess.UNRESTRICTED) {
                    SkillToolPolicyViolation(
                        message = "Interactive shell tools are not allowed by the active skills: ${activeSkillDirectoryNames.joinToString(", ")}"
                    )
                } else {
                    null
                }
            }

            else -> null
        }
    }

    private fun canRunSkillScripts(): Boolean {
        return shellAccess == SkillShellAccess.FULL || shellAccess == SkillShellAccess.UNRESTRICTED
    }

    private fun validateTermuxExec(input: JsonElement): SkillToolPolicyViolation? {
        return when (shellAccess) {
            SkillShellAccess.UNRESTRICTED,
            SkillShellAccess.FULL,
            -> null

            SkillShellAccess.NONE -> SkillToolPolicyViolation(
                message = "Shell execution is not allowed by the active skills: ${activeSkillDirectoryNames.joinToString(", ")}"
            )

            SkillShellAccess.READ_ONLY -> {
                val params = input.jsonObject
                val tty = params["tty"]?.jsonPrimitive?.booleanOrNull == true
                if (tty) {
                    return SkillToolPolicyViolation(
                        message = "Interactive shell sessions are not allowed while the active skills are read-only."
                    )
                }
                val command = params["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (!isReadOnlyShellCommand(command)) {
                    return SkillToolPolicyViolation(
                        message = "Command is not allowed by the active skills' read-only shell policy."
                    )
                }
                null
            }
        }
    }
}

private data class ParsedSkillToolRestriction(
    val visibleToolNames: Set<String>,
    val shellAccess: SkillShellAccess,
)

internal fun resolveSkillToolPolicy(
    activations: List<SkillActivationEntry>,
): SkillToolPolicy {
    if (activations.isEmpty()) return SkillToolPolicy()

    val restrictivePolicies = activations.mapNotNull { activation ->
        parseSkillToolRestriction(activation.entry.allowedTools)
    }
    if (restrictivePolicies.isEmpty()) {
        return SkillToolPolicy(
            activeSkillDirectoryNames = activations.map { it.entry.directoryName },
        )
    }

    val combinedVisibleToolNames = restrictivePolicies
        .map { it.visibleToolNames }
        .reduce { acc, tools -> acc intersect tools }

    val combinedShellAccess = restrictivePolicies
        .map { it.shellAccess }
        .reduce(SkillShellAccess::mostRestrictive)

    return SkillToolPolicy(
        activeSkillDirectoryNames = activations.map { it.entry.directoryName },
        visibleToolNames = combinedVisibleToolNames,
        shellAccess = combinedShellAccess,
    )
}

private fun parseSkillToolRestriction(
    rawAllowedTools: String?,
): ParsedSkillToolRestriction? {
    val normalized = rawAllowedTools
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null

    val visibleToolNames = linkedSetOf<String>()
    var shellAccess = SkillShellAccess.NONE

    normalized.split(SkillAllowedToolSplitRegex)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { token ->
            when (token.lowercase()) {
                "bash",
                "shell",
                "termux",
                "termux_exec",
                "write_stdin",
                "list_pty_sessions",
                "close_pty_session",
                -> {
                    shellAccess = SkillShellAccess.leastRestrictive(shellAccess, SkillShellAccess.FULL)
                    visibleToolNames += FullShellToolNames
                }

                "read",
                "readonly",
                "read-only",
                -> {
                    shellAccess = SkillShellAccess.leastRestrictive(shellAccess, SkillShellAccess.READ_ONLY)
                    visibleToolNames += ReadOnlyShellToolNames
                }

                "python",
                "termux_python",
                -> visibleToolNames += "termux_python"

                "time",
                "get_time_info",
                -> visibleToolNames += "get_time_info"

                "clipboard",
                "clipboard_tool",
                -> visibleToolNames += "clipboard_tool"

                "javascript",
                "js",
                "eval_javascript",
                -> visibleToolNames += "eval_javascript"

                "tts",
                "text_to_speech",
                -> visibleToolNames += "text_to_speech"

                "ask_user" -> visibleToolNames += "ask_user"
            }
        }

    return ParsedSkillToolRestriction(
        visibleToolNames = visibleToolNames,
        shellAccess = shellAccess,
    )
}

internal fun isReadOnlyShellCommand(command: String): Boolean {
    val normalized = command.trim()
    if (normalized.isBlank()) return false
    if (normalized.contains('\n') || normalized.contains('\r')) return false
    if (ReadOnlyShellOperatorsRegex.containsMatchIn(normalized)) return false

    val firstToken = normalized.substringBefore(' ')
    val executable = firstToken.substringAfterLast('/')
    val arguments = normalized.removePrefix(firstToken).trim()

    return when (executable) {
        in ReadOnlyShellCommands -> true
        "echo",
        "printf",
        -> true

        "sed" -> !Regex("""(^|\s)-i(?:\s|$)""").containsMatchIn(arguments)
        "git" -> arguments.substringBefore(' ').trim() in GitReadOnlySubcommands
        else -> false
    }
}
