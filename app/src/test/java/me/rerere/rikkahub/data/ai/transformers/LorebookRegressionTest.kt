package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookTriggerContext
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.StLorebookEntryExtension
import me.rerere.rikkahub.data.model.matchesTriggerKeywords
import me.rerere.rikkahub.data.model.withStExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LorebookRegressionTest {

    @Test
    fun `shared lorebook selection should keep all triggered entries`() {
        val highPriorityEntry = regexEntry(
            priority = 200,
            content = tokenContent(120),
        )
        val lowPriorityEntry = regexEntry(
            priority = 100,
            content = tokenContent(50),
        )

        val selected = selectTriggeredLorebookEntries(
            entries = listOf(
                ActivatedLorebookEntry(highPriorityEntry, LorebookScope.CHARACTER),
                ActivatedLorebookEntry(lowPriorityEntry, LorebookScope.CHARACTER),
            ),
        )

        assertEquals(listOf(highPriorityEntry.id, lowPriorityEntry.id), selected.map { it.id })
    }

    @Test
    fun `shared lorebook selection should prefer character scope before global scope`() {
        val characterEntry = regexEntry(priority = 10, content = "character")
        val globalEntry = regexEntry(priority = 100, content = "global")

        val selected = selectTriggeredLorebookEntries(
            entries = listOf(
                ActivatedLorebookEntry(globalEntry, LorebookScope.GLOBAL),
                ActivatedLorebookEntry(characterEntry, LorebookScope.CHARACTER),
            ),
        )

        assertEquals(listOf(characterEntry.id, globalEntry.id), selected.map { it.id })
    }

    @Test
    fun `local lorebook selection should keep priority order`() {
        val highPriorityEntry = regexEntry(
            priority = 200,
            content = tokenContent(50),
        )
        val lowPriorityEntry = regexEntry(
            priority = 100,
            content = tokenContent(100),
        )

        val selected = selectTriggeredCandidates(
            candidates = listOf(
                LorebookCandidate(entry = lowPriorityEntry, isSticky = false),
                LorebookCandidate(entry = highPriorityEntry, isSticky = false),
            ),
        )

        assertEquals(listOf(highPriorityEntry.id, lowPriorityEntry.id), selected.map { it.id })
    }

    @Test
    fun `constant active lorebook entries should ignore imported generation type triggers`() {
        val continueOnlyEntry = regexEntry(
            content = "Continue only",
            constantActive = true,
        ).withStExtension(
            StLorebookEntryExtension(triggers = listOf("continue"))
        )

        assertTrue(
            continueOnlyEntry.matchesTriggerKeywords(
                context = "",
                triggerContext = LorebookTriggerContext(
                    recentMessagesText = "",
                    generationType = "normal",
                ),
            )
        )
        assertTrue(
            continueOnlyEntry.matchesTriggerKeywords(
                context = "",
                triggerContext = LorebookTriggerContext(
                    recentMessagesText = "",
                    generationType = "continue",
                ),
            )
        )
    }

    @Test
    fun `resolve applicable lorebooks should ignore enabled lorebooks not linked to assistant`() {
        val assistant = Assistant(lorebookIds = setOf(Uuid.random()))
        val otherLorebookId = Uuid.random()
        val linkedLorebook = Lorebook(id = assistant.lorebookIds.first(), name = "Linked")
        val otherAssistant = Assistant(lorebookIds = setOf(otherLorebookId))
        val otherAssistantLorebook = Lorebook(id = otherLorebookId, name = "Other Assistant")
        val unassignedLorebook = Lorebook(id = Uuid.random(), name = "Draft")

        val applicable = resolveApplicableLorebooks(
            assistant = assistant,
            lorebooks = listOf(linkedLorebook, otherAssistantLorebook, unassignedLorebook),
            settings = Settings(
                assistants = listOf(assistant, otherAssistant),
            ),
        )

        assertEquals(listOf(linkedLorebook.id), applicable.map { it.lorebook.id })
        assertEquals(listOf(LorebookScope.CHARACTER), applicable.map { it.scope })
    }

    @Test
    fun `resolve applicable lorebooks should include explicit global lorebooks`() {
        val linkedLorebookId = Uuid.random()
        val globalLorebookId = Uuid.random()
        val assistant = Assistant(lorebookIds = setOf(linkedLorebookId))
        val linkedLorebook = Lorebook(id = linkedLorebookId, name = "Linked")
        val globalLorebook = Lorebook(id = globalLorebookId, name = "Global")
        val unassignedLorebook = Lorebook(id = Uuid.random(), name = "Draft")

        val applicable = resolveApplicableLorebooks(
            assistant = assistant,
            lorebooks = listOf(linkedLorebook, globalLorebook, unassignedLorebook),
            settings = Settings(
                assistants = listOf(assistant),
                globalLorebookIds = setOf(globalLorebookId),
            ),
        )

        assertEquals(
            listOf(linkedLorebook.id, globalLorebook.id),
            applicable.map { it.lorebook.id },
        )
        assertEquals(
            listOf(LorebookScope.CHARACTER, LorebookScope.GLOBAL),
            applicable.map { it.scope },
        )
    }

    private fun regexEntry(
        priority: Int = 0,
        content: String,
        constantActive: Boolean = false,
    ) = PromptInjection.RegexInjection(
        id = Uuid.random(),
        priority = priority,
        content = content,
        role = MessageRole.SYSTEM,
        keywords = if (constantActive) emptyList() else listOf("trigger"),
        constantActive = constantActive,
    )

    private fun tokenContent(tokenCount: Int): String {
        return (1..tokenCount).joinToString(" ") { index -> "w$index" }
    }
}
