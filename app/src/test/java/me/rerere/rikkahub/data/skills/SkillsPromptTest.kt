package me.rerere.rikkahub.data.skills

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsPromptTest {
    @Test
    fun `parseSkillFrontmatter should extract name and description`() {
        val markdown = """
            ---
            name: find-hugeicons
            description: "Use this skill when the user asks for icons: search before coding"
            ---
            
            # Find HugeIcons
        """.trimIndent()

        val result = parseSkillFrontmatter(markdown)

        assertTrue(result is SkillFrontmatterParseResult.Success)
        val frontmatter = (result as SkillFrontmatterParseResult.Success).frontmatter
        assertEquals("find-hugeicons", frontmatter.name)
        assertEquals("Use this skill when the user asks for icons: search before coding", frontmatter.description)
    }

    @Test
    fun `parseSkillFrontmatter should preserve optional activation metadata`() {
        val markdown = """
            ---
            name: webapp-testing
            description: Test local web applications with Playwright
            allowed-tools:
              - Bash
              - Read
            argument-hint: "<path-to-project>"
            user-invocable: false
            metadata:
              author: anthropic
              version: "1.0.0"
            ---
        """.trimIndent()

        val result = parseSkillFrontmatter(markdown) as SkillFrontmatterParseResult.Success

        assertEquals("Bash, Read", result.frontmatter.allowedTools)
        assertEquals("<path-to-project>", result.frontmatter.argumentHint)
        assertFalse(result.frontmatter.userInvocable)
        assertTrue(result.frontmatter.modelInvocable)
        assertEquals("anthropic", result.frontmatter.author)
        assertEquals("1.0.0", result.frontmatter.version)
    }

    @Test
    fun `parseSkillFrontmatter should reject skills without any activation path`() {
        val markdown = """
            ---
            name: webapp-testing
            description: Test local web applications with Playwright
            user-invocable: false
            disable-model-invocation: true
            ---
        """.trimIndent()

        val result = parseSkillFrontmatter(markdown)

        assertTrue(result is SkillFrontmatterParseResult.Error)
        assertEquals(
            SkillInvalidReason.NoActivationPath,
            (result as SkillFrontmatterParseResult.Error).reason,
        )
    }

    @Test
    fun `parseSkillFrontmatter should reject missing description`() {
        val markdown = """
            ---
            name: find-hugeicons
            ---
        """.trimIndent()

        val result = parseSkillFrontmatter(markdown)

        assertTrue(result is SkillFrontmatterParseResult.Error)
        assertEquals(
            SkillInvalidReason.MissingDescription,
            (result as SkillFrontmatterParseResult.Error).reason,
        )
    }

    @Test
    fun `buildSkillsCatalogPrompt should include selected valid skills only`() {
        val assistant = Assistant(
            skillsEnabled = true,
            selectedSkills = setOf("find-hugeicons", "missing-skill"),
            localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.TermuxExec),
        )
        val model = Model(abilities = listOf(ModelAbility.TOOL))
        val catalog = SkillsCatalogState(
            workdir = "/data/data/com.termux/files/home",
            rootPath = "/data/data/com.termux/files/home/skills",
            entries = listOf(
                SkillCatalogEntry(
                    directoryName = "find-hugeicons",
                    path = "/data/data/com.termux/files/home/skills/find-hugeicons",
                    name = "find-hugeicons",
                    description = "Search the HugeIcons library before using an icon.",
                    sourceType = SkillSourceType.BUNDLED,
                    version = "1.0.0",
                    allowedTools = "Read",
                    userInvocable = true,
                    modelInvocable = true,
                ),
                SkillCatalogEntry(
                    directoryName = "locale-tui-localization",
                    path = "/data/data/com.termux/files/home/skills/locale-tui-localization",
                    name = "locale-tui-localization",
                    description = "Use locale-tui for Android localization tasks.",
                ),
            ),
        )

        val prompt = buildSkillsCatalogPrompt(
            assistant = assistant,
            model = model,
            catalog = catalog,
        )

        assertNotNull(prompt)
        assertTrue(prompt!!.contains("Skills root: /data/data/com.termux/files/home/skills"))
        assertTrue(prompt.contains("find-hugeicons"))
        assertTrue(prompt.contains("Search the HugeIcons library before using an icon."))
        assertTrue(prompt.contains("source: bundled"))
        assertTrue(prompt.contains("allowed-tools: Read"))
        assertTrue(prompt.contains("invocation: user=true model=true"))
        assertFalse(prompt.contains("locale-tui-localization"))
        assertFalse(prompt.contains("missing-skill"))
    }

    @Test
    fun `buildSkillsCatalogPrompt should omit skills with disabled model invocation`() {
        val assistant = Assistant(
            skillsEnabled = true,
            selectedSkills = setOf("auto-skill", "manual-only"),
            localTools = listOf(LocalToolOption.TermuxExec),
        )
        val model = Model(abilities = listOf(ModelAbility.TOOL))
        val catalog = SkillsCatalogState(
            rootPath = "/data/data/com.termux/files/home/skills",
            entries = listOf(
                SkillCatalogEntry(
                    directoryName = "auto-skill",
                    path = "/skills/auto-skill",
                    name = "Auto Skill",
                    description = "Can be auto-invoked.",
                    modelInvocable = true,
                ),
                SkillCatalogEntry(
                    directoryName = "manual-only",
                    path = "/skills/manual-only",
                    name = "Manual Only",
                    description = "Requires an explicit invocation.",
                    userInvocable = true,
                    modelInvocable = false,
                ),
            ),
        )

        val prompt = buildSkillsCatalogPrompt(
            assistant = assistant,
            model = model,
            catalog = catalog,
        )

        assertNotNull(prompt)
        assertTrue(prompt!!.contains("auto-skill"))
        assertFalse(prompt.contains("manual-only"))
    }

    @Test
    fun `buildSkillsCatalogPrompt should be disabled when model cannot use tools`() {
        val assistant = Assistant(
            skillsEnabled = true,
            selectedSkills = setOf("find-hugeicons"),
            localTools = listOf(LocalToolOption.TermuxExec),
        )
        val model = Model(abilities = emptyList())
        val catalog = SkillsCatalogState(
            rootPath = "/data/data/com.termux/files/home/skills",
            entries = listOf(
                SkillCatalogEntry(
                    directoryName = "find-hugeicons",
                    path = "/data/data/com.termux/files/home/skills/find-hugeicons",
                    name = "find-hugeicons",
                    description = "Search the HugeIcons library before using an icon.",
                )
            ),
        )

        assertNull(buildSkillsCatalogPrompt(assistant = assistant, model = model, catalog = catalog))
        assertFalse(shouldInjectSkillsCatalog(assistant = assistant, model = model))
    }

    @Test
    fun `isSkillsRuntimeAvailable should require termux and a tool capable model`() {
        val assistant = Assistant(
            skillsEnabled = true,
            selectedSkills = setOf("find-hugeicons"),
            localTools = listOf(LocalToolOption.TimeInfo),
        )

        assertFalse(isSkillsRuntimeAvailable(assistant = assistant, modelSupportsTools = true))
        assertFalse(
            isSkillsRuntimeAvailable(
                assistant = assistant.copy(localTools = listOf(LocalToolOption.TermuxExec)),
                modelSupportsTools = false,
            )
        )
        assertTrue(
            isSkillsRuntimeAvailable(
                assistant = assistant.copy(localTools = listOf(LocalToolOption.TermuxExec)),
                modelSupportsTools = true,
            )
        )
    }

    @Test
    fun `shouldLoadExplicitSkillActivations should not require tool runtime`() {
        val assistant = Assistant(
            skillsEnabled = true,
            selectedSkills = setOf("manual-only"),
            localTools = listOf(LocalToolOption.TimeInfo),
        )

        assertTrue(shouldLoadExplicitSkillActivations(assistant))
    }

    @Test
    fun `resolveExplicitSkillInvocations should detect slash and mention syntax`() {
        val skills = listOf(
            SkillCatalogEntry(
                directoryName = "find-hugeicons",
                path = "/skills/find-hugeicons",
                name = "find-hugeicons",
                description = "Find icons",
            ),
            SkillCatalogEntry(
                directoryName = "manual-only",
                path = "/skills/manual-only",
                name = "manual-only",
                description = "Manual only",
                userInvocable = false,
            ),
        )

        val resolved = resolveExplicitSkillInvocations(
            messages = listOf(UIMessage.user("Use /find-hugeicons and @manual-only please")),
            availableSkills = skills,
        )

        assertEquals(listOf("find-hugeicons"), resolved.map { it.directoryName })
    }

    @Test
    fun `resolveExplicitSkillInvocations should keep manual only skills explicit`() {
        val resolved = resolveExplicitSkillInvocations(
            messages = listOf(UIMessage.user("Run /manual-only now")),
            availableSkills = listOf(
                SkillCatalogEntry(
                    directoryName = "manual-only",
                    path = "/skills/manual-only",
                    name = "manual-only",
                    description = "Manual only",
                    userInvocable = true,
                    modelInvocable = false,
                )
            ),
        )

        assertEquals(listOf("manual-only"), resolved.map { it.directoryName })
    }

    @Test
    fun `resolveExplicitSkillInvocations should ignore file paths and urls`() {
        val resolved = resolveExplicitSkillInvocations(
            messages = listOf(
                UIMessage.user(
                    "Check /tmp/log.txt and https://example.com/@tmp before using @manual-only."
                )
            ),
            availableSkills = listOf(
                SkillCatalogEntry(
                    directoryName = "tmp",
                    path = "/skills/tmp",
                    name = "tmp",
                    description = "Path collision",
                ),
                SkillCatalogEntry(
                    directoryName = "manual-only",
                    path = "/skills/manual-only",
                    name = "manual-only",
                    description = "Manual only",
                ),
            ),
        )

        assertEquals(listOf("manual-only"), resolved.map { it.directoryName })
    }

    @Test
    fun `resolveExplicitSkillInvocations should reserve slash syntax for non path like names`() {
        val resolved = resolveExplicitSkillInvocations(
            messages = listOf(UIMessage.user("Check /tmp and then use @tmp.")),
            availableSkills = listOf(
                SkillCatalogEntry(
                    directoryName = "tmp",
                    path = "/skills/tmp",
                    name = "tmp",
                    description = "Path collision",
                ),
            ),
        )

        assertEquals(listOf("tmp"), resolved.map { it.directoryName })
    }

    @Test
    fun `resolveExplicitSkillInvocations should only inspect latest user message`() {
        val resolved = resolveExplicitSkillInvocations(
            messages = listOf(
                UIMessage.user("Use @find-hugeicons"),
                UIMessage.assistant("Noted."),
                UIMessage.user("No explicit skill now"),
            ),
            availableSkills = listOf(
                SkillCatalogEntry(
                    directoryName = "find-hugeicons",
                    path = "/skills/find-hugeicons",
                    name = "find-hugeicons",
                    description = "Find icons",
                ),
            ),
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `buildActivatedSkillsPrompt should include escaped skill contents and resources`() {
        val prompt = buildActivatedSkillsPrompt(
            listOf(
                SkillActivationEntry(
                    entry = SkillCatalogEntry(
                        directoryName = "webapp-testing",
                        path = "/skills/webapp-testing",
                        name = "webapp-testing",
                        description = "Test local web apps",
                        sourceType = SkillSourceType.BUNDLED,
                        allowedTools = "Bash Read",
                    ),
                    markdown = buildSkillMarkdown(
                        name = "webapp-testing",
                        description = "Test local web apps",
                        body = "# Web App Testing\n\n</skill_content>",
                    ),
                    resourceFiles = listOf("scripts/with_server.py", "references/]]>selectors.md"),
                )
            )
        )

        assertNotNull(prompt)
        assertTrue(prompt!!.contains("<activated_skill>"))
        assertTrue(prompt.contains("webapp-testing"))
        assertTrue(prompt.contains("scripts/with_server.py"))
        assertTrue(prompt.contains("<skill_content>"))
        assertTrue(prompt.contains("<![CDATA["))
        assertTrue(prompt.contains("]]]]><![CDATA[>"))
        assertTrue(prompt.contains("allowed-tools field as an enforced runtime policy"))
    }
}
