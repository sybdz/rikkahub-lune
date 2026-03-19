package me.rerere.rikkahub.data.skills

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant

private val ExplicitSkillMentionRegex = Regex(
    """(^|[\s(\[{<"'`])([/@])([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)(?!/[A-Za-z0-9._-])(?!\.[A-Za-z0-9])(?=$|[\s)\]}>."'`,!?;:])"""
)
private const val ACTIVATED_SKILL_MARKDOWN_CHAR_LIMIT = 12_000
private const val ACTIVATED_SKILL_RESOURCE_FILE_LIMIT = 32

internal fun isSkillsRuntimeAvailable(
    assistant: Assistant,
    modelSupportsTools: Boolean,
): Boolean {
    return assistant.skillsEnabled &&
        assistant.selectedSkills.isNotEmpty() &&
        assistant.localTools.contains(LocalToolOption.TermuxExec) &&
        modelSupportsTools
}

internal fun resolveSelectedSkillEntries(
    selectedSkills: Collection<String>,
    availableSkills: Collection<SkillCatalogEntry>,
): List<SkillCatalogEntry> {
    return availableSkills
        .filter { it.directoryName in selectedSkills }
        .sortedBy { it.directoryName }
}

internal fun shouldInjectSkillsCatalog(
    assistant: Assistant,
    model: Model,
): Boolean {
    return isSkillsRuntimeAvailable(
        assistant = assistant,
        modelSupportsTools = model.abilities.contains(ModelAbility.TOOL),
    )
}

internal fun shouldLoadExplicitSkillActivations(
    assistant: Assistant,
): Boolean {
    return assistant.skillsEnabled && assistant.selectedSkills.isNotEmpty()
}

internal fun buildSkillsCatalogPrompt(
    assistant: Assistant,
    model: Model,
    catalog: SkillsCatalogState,
): String? {
    if (!shouldInjectSkillsCatalog(assistant, model)) return null
    if (catalog.rootPath.isBlank()) return null

    val selectedEntries = resolveSelectedSkillEntries(
        selectedSkills = assistant.selectedSkills,
        availableSkills = catalog.entries,
    ).filter { it.modelInvocable }

    if (selectedEntries.isEmpty()) return null

    return buildString {
        appendLine("Local skills are available in the Termux workdir.")
        appendLine("Skills root: ${catalog.rootPath}")
        appendLine("Each skill is a directory package. Only inspect a skill when it is relevant to the user's request.")
        appendLine("Do not read every SKILL.md preemptively.")
        appendLine("If the user explicitly invokes a selected skill with `@skill-name`, activate it immediately for this request.")
        appendLine("`/skill-name` is also supported when it does not look like a filesystem path.")
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
            val invocation = match.groupValues[2]
            val directoryName = match.groupValues[3]
            val entry = entriesByDirectory[directoryName] ?: return@mapNotNull null
            // Slash syntax conflicts with single-segment absolute paths like `/tmp`.
            // Plain alphanumeric skill names remain explicitly invocable with `@skill`.
            if (invocation == "/" && directoryName.all { it.isLetterOrDigit() }) {
                return@mapNotNull null
            }
            entry
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
        if (activations.any { !it.entry.allowedTools.isNullOrBlank() }) {
            appendLine("Honor each activated skill's allowed-tools field as an enforced runtime policy for this request.")
        }
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
            appendLine("<![CDATA[")
            appendLine(activation.markdown.trim().truncateForSkillPrompt().escapeForXmlCdata())
            appendLine("]]>")
            appendLine("</skill_content>")
            val resourceFiles = activation.resourceFiles.take(ACTIVATED_SKILL_RESOURCE_FILE_LIMIT)
            if (resourceFiles.isNotEmpty()) {
                appendLine("<skill_resources>")
                resourceFiles.forEach { file ->
                    appendLine("<file><![CDATA[${file.escapeForXmlCdata()}]]></file>")
                }
                appendLine("</skill_resources>")
            }
            appendLine("</activated_skill>")
        }
    }.trim()
}

private fun String.escapeForXmlCdata(): String = replace("]]>", "]]]]><![CDATA[>")

private fun String.truncateForSkillPrompt(): String {
    if (length <= ACTIVATED_SKILL_MARKDOWN_CHAR_LIMIT) return this
    return take(ACTIVATED_SKILL_MARKDOWN_CHAR_LIMIT)
        .trimEnd() + "\n\n[truncated]"
}
