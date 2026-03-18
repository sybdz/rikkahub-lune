package me.rerere.rikkahub.data.skills

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant

private val ExplicitSkillMentionRegex = Regex("""(?<![A-Za-z0-9._-])(?:/|@)([A-Za-z0-9._-]+)\b""")

internal fun shouldInjectSkillsCatalog(
    assistant: Assistant,
    model: Model,
): Boolean {
    return assistant.skillsEnabled &&
        assistant.selectedSkills.isNotEmpty() &&
        assistant.localTools.contains(LocalToolOption.TermuxExec) &&
        model.abilities.contains(ModelAbility.TOOL)
}

internal fun buildSkillsCatalogPrompt(
    assistant: Assistant,
    model: Model,
    catalog: SkillsCatalogState,
): String? {
    if (!shouldInjectSkillsCatalog(assistant, model)) return null
    if (catalog.rootPath.isBlank()) return null

    val selectedEntries = catalog.entries
        .filter { it.directoryName in assistant.selectedSkills }
        .sortedBy { it.directoryName }

    if (selectedEntries.isEmpty()) return null

    return buildString {
        appendLine("Local skills are available in the Termux workdir.")
        appendLine("Skills root: ${catalog.rootPath}")
        appendLine("Each skill is a directory package. Only inspect a skill when it is relevant to the user's request.")
        appendLine("Do not read every SKILL.md preemptively.")
        appendLine("If the user explicitly invokes a selected skill with `/skill-name` or `@skill-name`, activate it immediately for this request.")
        appendLine("When a skill is relevant, use the existing Termux tools to inspect files such as SKILL.md or run scripts inside that skill directory.")
        appendLine()
        appendLine("Available skills:")
        selectedEntries.forEach { skill ->
            appendLine("- directory: ${skill.directoryName}")
            appendLine("  name: ${skill.name}")
            appendLine("  description: ${skill.description}")
            appendLine("  source: ${skill.sourceType.name.lowercase()}")
            skill.version?.let { appendLine("  version: $it") }
            skill.author?.let { appendLine("  author: $it") }
            skill.argumentHint?.let { appendLine("  argument-hint: $it") }
            skill.allowedTools?.let { appendLine("  allowed-tools: $it") }
            skill.compatibility?.let { appendLine("  compatibility: $it") }
            appendLine("  invocation: user=${skill.userInvocable} model=${skill.modelInvocable}")
            appendLine("  path: ${skill.path}")
        }
    }.trim()
}

internal fun resolveExplicitSkillInvocations(
    messages: List<UIMessage>,
    availableSkills: Collection<SkillCatalogEntry>,
): List<SkillCatalogEntry> {
    val latestUserText = messages.lastOrNull { it.role == MessageRole.USER }
        ?.toText()
        ?.takeIf { it.isNotBlank() }
        ?: return emptyList()
    val entriesByDirectory = availableSkills.associateBy { it.directoryName }
    return ExplicitSkillMentionRegex.findAll(latestUserText)
        .mapNotNull { match ->
            entriesByDirectory[match.groupValues[1]]
        }
        .filter { it.userInvocable }
        .distinctBy { it.directoryName }
        .toList()
}

internal fun buildActivatedSkillsPrompt(
    activations: List<SkillActivationEntry>,
): String? {
    if (activations.isEmpty()) return null
    return buildString {
        appendLine("The following local skills were explicitly activated for this request.")
        appendLine("Treat their SKILL.md instructions as active guidance for this response.")
        activations.forEach { activation ->
            appendLine()
            appendLine("<activated_skill>")
            appendLine("directory: ${activation.entry.directoryName}")
            appendLine("name: ${activation.entry.name}")
            appendLine("source: ${activation.entry.sourceType.name.lowercase()}")
            activation.entry.version?.let { appendLine("version: $it") }
            activation.entry.allowedTools?.let { appendLine("allowed-tools: $it") }
            activation.entry.compatibility?.let { appendLine("compatibility: $it") }
            appendLine("path: ${activation.entry.path}")
            appendLine("<skill_content>")
            appendLine(activation.markdown.trim())
            appendLine("</skill_content>")
            if (activation.resourceFiles.isNotEmpty()) {
                appendLine("<skill_resources>")
                activation.resourceFiles.forEach { file ->
                    appendLine("- $file")
                }
                appendLine("</skill_resources>")
            }
            appendLine("</activated_skill>")
        }
    }.trim()
}
