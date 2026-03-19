package me.rerere.rikkahub.data.skills

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
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
        (assistant.skillsCatalogEnabled || assistant.skillsScriptExecutionEnabled) &&
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
    return assistant.skillsEnabled &&
        assistant.skillsCatalogEnabled &&
        assistant.selectedSkills.isNotEmpty() &&
        model.abilities.contains(ModelAbility.TOOL)
}

internal fun shouldLoadExplicitSkillActivations(
    assistant: Assistant,
): Boolean {
    return assistant.skillsEnabled &&
        assistant.skillsExplicitInvocationEnabled &&
        assistant.selectedSkills.isNotEmpty()
}

internal fun buildSkillsCatalogPrompt(
    assistant: Assistant,
    model: Model,
    catalog: SkillsCatalogState,
): String? {
    if (!shouldInjectSkillsCatalog(assistant, model)) return null

    val selectedEntries = resolveSelectedSkillEntries(
        selectedSkills = assistant.selectedSkills,
        availableSkills = catalog.entries,
    ).filter { it.modelInvocable }

    if (selectedEntries.isEmpty()) return null

    return buildString {
        appendLine("Selected local skills are available for this assistant.")
        appendLine("Use `activate_skill` to load a selected skill's SKILL.md only when it is relevant.")
        appendLine("After `activate_skill` succeeds, `read_skill_resource` becomes available for text resources from that activated skill.")
        if (assistant.skillsScriptExecutionEnabled) {
            appendLine("After activation, `run_skill_script` becomes available for scripts inside that skill package when the skill explicitly requires it.")
        }
        appendLine("Do not activate every skill preemptively.")
        appendLine("If the user explicitly invokes a selected skill with `@skill-name`, treat it as already activated for this request.")
        appendLine("`/skill-name` is also supported when it does not look like a filesystem path.")
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

internal fun resolveToolActivatedSkillInvocations(
    messages: List<UIMessage>,
    availableSkills: Collection<SkillCatalogEntry>,
): List<SkillCatalogEntry> {
    val latestUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
    if (latestUserIndex < 0) return emptyList()

    val entriesByDirectory = availableSkills.associateBy { it.directoryName }
    return messages.asSequence()
        .drop(latestUserIndex + 1)
        .flatMap { message -> message.getTools().asSequence() }
        .filter { tool -> tool.toolName == "activate_skill" && tool.isExecuted }
        .mapNotNull { tool ->
            val directoryName = tool.inputAsJson()
                .jsonObject["skill"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?: return@mapNotNull null
            entriesByDirectory[directoryName]
        }
        .distinctBy { it.directoryName }
        .toList()
}

internal fun buildActivatedSkillsPrompt(
    activations: List<SkillActivationEntry>,
): String? {
    if (activations.isEmpty()) return null
    return buildString {
        appendLine("The following local skills were activated for this request.")
        appendLine("Treat their SKILL.md instructions as active guidance for this response.")
        appendLine("Use `read_skill_resource` only for listed files with `text-readable=true`.")
        appendLine("Use `run_skill_script` for listed files with `kind=script` when the skill explicitly requires it.")
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
            if (activation.entry.lintWarnings.isNotEmpty()) {
                appendLine("<skill_warnings>")
                activation.entry.lintWarnings.forEach { warning ->
                    appendLine("<warning><![CDATA[${warning.escapeForXmlCdata()}]]></warning>")
                }
                appendLine("</skill_warnings>")
            }
            if (activation.entry.compatibilityNotes.isNotEmpty()) {
                appendLine("<skill_compatibility_notes>")
                activation.entry.compatibilityNotes.forEach { note ->
                    appendLine("<note><![CDATA[${note.escapeForXmlCdata()}]]></note>")
                }
                appendLine("</skill_compatibility_notes>")
            }
            appendLine("path: ${activation.entry.path}")
            appendLine("<skill_content>")
            appendLine("<![CDATA[")
            appendLine(activation.markdown.trim().truncateForSkillPrompt().escapeForXmlCdata())
            appendLine("]]>")
            appendLine("</skill_content>")
            val resourceFiles = activation.resourceIndex.take(ACTIVATED_SKILL_RESOURCE_FILE_LIMIT)
            if (resourceFiles.isNotEmpty()) {
                appendLine("<skill_resources>")
                resourceFiles.forEach { file ->
                    appendLine(
                        """<file kind="${file.kind.name.lowercase()}" text-readable="${file.textReadable}"><![CDATA[${file.path.escapeForXmlCdata()}]]></file>"""
                    )
                }
                appendLine("</skill_resources>")
            }
            appendLine("</activated_skill>")
        }
    }.trim()
}

private fun String.escapeForXmlCdata(): String = replace("]]>", "]]]]><![CDATA[>")

internal fun String.truncateForSkillPrompt(): String {
    if (length <= ACTIVATED_SKILL_MARKDOWN_CHAR_LIMIT) return this
    return take(ACTIVATED_SKILL_MARKDOWN_CHAR_LIMIT)
        .trimEnd() + "\n\n[truncated]"
}
