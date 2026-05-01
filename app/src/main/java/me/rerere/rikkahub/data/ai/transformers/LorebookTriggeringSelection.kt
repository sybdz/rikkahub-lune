package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection

internal data class LorebookCandidate(
    val entry: PromptInjection.RegexInjection,
    val isSticky: Boolean,
)

internal enum class LorebookScope {
    CHARACTER,
    GLOBAL,
}

internal data class ScopedLorebook(
    val lorebook: Lorebook,
    val scope: LorebookScope,
)

internal data class ActivatedLorebookEntry(
    val entry: PromptInjection.RegexInjection,
    val scope: LorebookScope,
)

internal fun resolveApplicableLorebooks(
    assistant: Assistant,
    lorebooks: List<Lorebook>,
    settings: Settings?,
): List<ScopedLorebook> {
    val globalLorebookIds = settings?.globalLorebookIds.orEmpty()
    return lorebooks
        .asSequence()
        .filter { lorebook ->
            lorebook.enabled &&
                (
                    assistant.lorebookIds.contains(lorebook.id) ||
                        lorebook.id in globalLorebookIds
                )
        }
        .map { lorebook ->
            ScopedLorebook(
                lorebook = lorebook,
                scope = if (assistant.lorebookIds.contains(lorebook.id)) {
                    LorebookScope.CHARACTER
                } else {
                    LorebookScope.GLOBAL
                },
            )
        }
        .toList()
}

internal fun selectTriggeredLorebookEntries(
    entries: List<ActivatedLorebookEntry>,
): List<PromptInjection.RegexInjection> {
    if (entries.isEmpty()) return emptyList()

    return entries.sortedWith(
        compareBy<ActivatedLorebookEntry> { if (it.scope == LorebookScope.CHARACTER) 0 else 1 }
            .thenByDescending { it.entry.priority },
    ).map { it.entry }
}

internal fun selectTriggeredCandidates(
    candidates: List<LorebookCandidate>,
): List<PromptInjection.RegexInjection> {
    if (candidates.isEmpty()) return emptyList()
    return candidates.sortedWith(
        compareByDescending<LorebookCandidate> { it.isSticky }
            .thenByDescending { it.entry.priority },
    ).map { it.entry }
}

internal fun estimateLorebookTokenCount(text: String): Int {
    val content = text.trim()
    if (content.isEmpty()) return 0
    return Regex("""\p{L}[\p{L}\p{N}_'-]*|\p{N}+|[^\s]""")
        .findAll(content)
        .count()
}
