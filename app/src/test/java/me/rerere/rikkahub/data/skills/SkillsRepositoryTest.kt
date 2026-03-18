package me.rerere.rikkahub.data.skills

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.ai.tools.termux.TermuxRunCommandRequest

class SkillsRepositoryTest {
    @Test
    fun `toRefreshingCatalogState should keep cached entries when refreshing same root`() {
        val initial = SkillsCatalogState(
            workdir = "/old",
            rootPath = "/old/skills",
            entries = listOf(
                SkillCatalogEntry(
                    directoryName = "old-skill",
                    path = "/old/skills/old-skill",
                    name = "old-skill",
                    description = "Old description",
                )
            ),
            invalidEntries = listOf(
                SkillInvalidEntry(
                    directoryName = "broken-skill",
                    path = "/old/skills/broken-skill",
                    reason = SkillInvalidReason.MissingSkillFile,
                )
            ),
            error = "Previous failure",
            refreshedAt = 123L,
        )

        val loading = initial.toRefreshingCatalogState(
            workdir = "/old",
            rootPath = "/old/skills",
        )

        assertEquals("/old", loading.workdir)
        assertEquals("/old/skills", loading.rootPath)
        assertEquals(initial.entries, loading.entries)
        assertEquals(initial.invalidEntries, loading.invalidEntries)
        assertTrue(loading.isLoading)
        assertNull(loading.error)
        assertEquals(123L, loading.refreshedAt)
    }

    @Test
    fun `toRefreshingCatalogState should clear cached entries when root changes`() {
        val initial = SkillsCatalogState(
            workdir = "/old",
            rootPath = "/old/skills",
            entries = listOf(
                SkillCatalogEntry(
                    directoryName = "old-skill",
                    path = "/old/skills/old-skill",
                    name = "old-skill",
                    description = "Old description",
                )
            ),
            invalidEntries = listOf(
                SkillInvalidEntry(
                    directoryName = "broken-skill",
                    path = "/old/skills/broken-skill",
                    reason = SkillInvalidReason.MissingSkillFile,
                )
            ),
        )

        val loading = initial.toRefreshingCatalogState(
            workdir = "/new",
            rootPath = "/new/skills",
        )

        assertEquals("/new", loading.workdir)
        assertEquals("/new/skills", loading.rootPath)
        assertTrue(loading.entries.isEmpty())
        assertTrue(loading.invalidEntries.isEmpty())
        assertTrue(loading.isLoading)
        assertNull(loading.error)
    }

    @Test
    fun `discoverCatalogEntries should keep valid entries when one skill file is unreadable`() = runBlocking {
        val result = discoverCatalogEntries(
            directories = listOf(
                SkillDirectoryDescriptor(
                    directoryName = "alpha",
                    path = "/skills/alpha",
                    hasSkillFile = true,
                ),
                SkillDirectoryDescriptor(
                    directoryName = "broken",
                    path = "/skills/broken",
                    hasSkillFile = true,
                ),
            ),
            readSkillFile = { path ->
                when (path) {
                    "/skills/alpha" -> {
                        """
                        ---
                        name: alpha
                        description: Valid skill
                        ---
                        """.trimIndent()
                    }

                    "/skills/broken" -> error("Permission denied")
                    else -> error("Unexpected path: $path")
                }
            },
        )

        assertEquals(
            listOf(
                SkillCatalogEntry(
                    directoryName = "alpha",
                    path = "/skills/alpha",
                    name = "alpha",
                    description = "Valid skill",
                )
            ),
            result.entries,
        )
        assertEquals(1, result.invalidEntries.size)
        assertEquals("broken", result.invalidEntries.single().directoryName)
        assertEquals("/skills/broken", result.invalidEntries.single().path)
        assertEquals(
            SkillInvalidReason.FailedToRead("Permission denied"),
            result.invalidEntries.single().reason,
        )
    }

    @Test
    fun `discoverCatalogEntries should use preview content without reading skill file again`() = runBlocking {
        val result = discoverCatalogEntries(
            directories = listOf(
                SkillDirectoryDescriptor(
                    directoryName = "skill-creator",
                    path = "/skills/skill-creator",
                    hasSkillFile = true,
                    skillMarkdownPreview = buildSkillMarkdown(
                        name = "Skill Creator",
                        description = "Built in",
                        body = "",
                    ),
                    sourceMetadataPreview = """
                        {
                          "sourceType": "BUNDLED",
                          "sourceId": "skill-creator",
                          "hash": "abc"
                        }
                    """.trimIndent(),
                )
            ),
            readSkillFile = { error("Should not read full skill file when preview is available") },
        )

        assertEquals(1, result.entries.size)
        assertEquals("skill-creator", result.entries.single().directoryName)
        assertEquals("Skill Creator", result.entries.single().name)
        assertTrue(result.entries.single().isBundled)
        assertTrue(result.invalidEntries.isEmpty())
    }

    @Test
    fun `discoverCatalogEntries should retry full skill read when preview is truncated`() = runBlocking {
        val result = discoverCatalogEntries(
            directories = listOf(
                SkillDirectoryDescriptor(
                    directoryName = "demo",
                    path = "/skills/demo",
                    hasSkillFile = true,
                    skillMarkdownPreview = """
                        ---
                        name: demo
                    """.trimIndent(),
                )
            ),
            readSkillFile = {
                buildSkillMarkdown(
                    name = "Demo",
                    description = "Recovered from full read",
                    body = "",
                )
            },
        )

        assertEquals(1, result.entries.size)
        assertEquals("Demo", result.entries.single().name)
        assertTrue(result.invalidEntries.isEmpty())
    }

    @Test
    fun `buildSkillCommandRequest should always use background Termux execution`() {
        val request = buildSkillCommandRequest(
            script = "echo ok",
            workdir = "/data/data/com.termux/files/home",
            label = "RikkaHub list local skills",
        )

        assertEquals(
            TermuxRunCommandRequest(
                commandPath = "/data/data/com.termux/files/usr/bin/bash",
                arguments = listOf("-lc", "echo ok"),
                workdir = "/data/data/com.termux/files/home",
                background = true,
                timeoutMs = 30_000L,
                label = "RikkaHub list local skills",
            ),
            request,
        )
    }

    @Test
    fun `sanitizeSkillDirectoryName should normalize unsupported characters`() {
        assertEquals("my-skill-v2", sanitizeSkillDirectoryName(" My Skill V2! "))
        assertEquals("skill-import", sanitizeSkillDirectoryName("技能包", fallback = "skill-import"))
    }

    @Test
    fun `buildSkillMarkdown should roundtrip quoted frontmatter values`() {
        val markdown = buildSkillMarkdown(
            name = "Alice \"Helper\"",
            description = "Line one",
            body = "",
        )

        val parsed = parseSkillFrontmatter(markdown)
        assertTrue(parsed is SkillFrontmatterParseResult.Success)
        parsed as SkillFrontmatterParseResult.Success
        assertEquals("Alice \"Helper\"", parsed.frontmatter.name)
        assertEquals("Line one", parsed.frontmatter.description)
        assertTrue(markdown.contains("# Instructions"))
    }

    @Test
    fun `buildSkillMarkdown should preserve supported optional frontmatter fields`() {
        val markdown = buildSkillMarkdown(
            name = "Skill Creator",
            description = "Create and optimize skills",
            body = "",
            extras = SkillFrontmatterExtras(
                license = "Apache-2.0",
                compatibility = "termux",
                allowedTools = "Bash Read",
                argumentHint = "<path-to-skill>",
                userInvocable = false,
                disableModelInvocation = true,
                metadata = mapOf(
                    "author" to "anthropic",
                    "version" to "1.2.3",
                ),
            ),
        )

        val parsed = parseSkillFrontmatter(markdown) as SkillFrontmatterParseResult.Success
        assertEquals("Apache-2.0", parsed.frontmatter.license)
        assertEquals("termux", parsed.frontmatter.compatibility)
        assertEquals("Bash Read", parsed.frontmatter.allowedTools)
        assertEquals("<path-to-skill>", parsed.frontmatter.argumentHint)
        assertFalse(parsed.frontmatter.userInvocable)
        assertFalse(parsed.frontmatter.modelInvocable)
        assertEquals("anthropic", parsed.frontmatter.author)
        assertEquals("1.2.3", parsed.frontmatter.version)
    }

    @Test
    fun `parseSkillMarkdownDocument should return body without frontmatter`() {
        val markdown = buildSkillMarkdown(
            name = "Demo",
            description = "Desc",
            body = "# Steps\n\nDo the thing.",
        )

        val document = parseSkillMarkdownDocument(markdown)

        assertEquals("Demo", document.frontmatter.name)
        assertEquals("Desc", document.frontmatter.description)
        assertEquals("# Steps\n\nDo the thing.", document.body)
    }

    @Test
    fun `normalizeSkillArchiveEntryPath should reject traversal`() {
        val result = runCatching {
            normalizeSkillArchiveEntryPath("../danger.sh")
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `normalizeSkillArchiveEntryPath should reject control characters`() {
        val result = runCatching {
            normalizeSkillArchiveEntryPath("demo/\tbad.txt")
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `collapseSkillArchiveContainerLayers should strip common outer folder`() {
        val collapsed = collapseSkillArchiveContainerLayers(
            ParsedSkillArchive(
                directories = linkedSetOf("skills", "skills/demo", "skills/demo/scripts"),
                files = listOf(
                    SkillArchiveFile("skills/demo/SKILL.md", "---\nname: demo\ndescription: ok\n---".toByteArray()),
                    SkillArchiveFile("skills/demo/scripts/run.sh", "echo ok".toByteArray()),
                ),
            )
        )

        assertEquals(setOf("demo", "demo/scripts"), collapsed.directories)
        assertEquals(
            listOf("demo/SKILL.md", "demo/scripts/run.sh"),
            collapsed.files.map { it.path },
        )
    }

    @Test
    fun `buildSkillImportPlan should wrap root files and suffix conflicting directory names`() {
        val archive = ParsedSkillArchive(
            directories = linkedSetOf("scripts"),
            files = listOf(
                SkillArchiveFile(
                    path = "SKILL.md",
                    bytes = buildSkillMarkdown(
                        name = "Demo Skill",
                        description = "Imported",
                        body = "",
                    ).toByteArray()
                ),
                SkillArchiveFile(
                    path = "scripts/run.sh",
                    bytes = "echo ok".toByteArray(),
                ),
            ),
        )

        val plan = buildSkillImportPlan(
            archive = archive,
            suggestedDirectoryName = "demo-skill",
            existingDirectoryNames = setOf("demo-skill"),
        )

        assertEquals(listOf("demo-skill-2"), plan.topLevelDirectories)
        assertEquals(
            setOf("demo-skill-2", "demo-skill-2/scripts"),
            plan.directories,
        )
        assertEquals(
            setOf("demo-skill-2/SKILL.md", "demo-skill-2/scripts/run.sh"),
            plan.files.map { it.path }.toSet(),
        )
    }

    @Test
    fun `buildSkillImportPreview should summarize imported skills and scripts`() {
        val archive = ParsedSkillArchive(
            directories = linkedSetOf("scripts", "assets"),
            files = listOf(
                SkillArchiveFile(
                    path = "SKILL.md",
                    bytes = buildSkillMarkdown(
                        name = "Demo Skill",
                        description = "Imported",
                        body = "",
                        extras = SkillFrontmatterExtras(
                            metadata = mapOf(
                                "author" to "tester",
                                "version" to "2.0.0",
                            )
                        ),
                    ).toByteArray()
                ),
                SkillArchiveFile(
                    path = "scripts/run.sh",
                    bytes = "echo ok".toByteArray(),
                ),
                SkillArchiveFile(
                    path = "assets/template.txt",
                    bytes = "template".toByteArray(),
                ),
            ),
        )

        val preview = buildSkillImportPreview(
            archive = archive,
            suggestedDirectoryName = "demo-skill",
            existingDirectoryNames = setOf("demo-skill"),
            archiveName = "demo.zip",
        ).preview

        assertEquals(listOf("demo-skill-2"), preview.directories)
        assertEquals(3, preview.totalFiles)
        assertEquals(1, preview.scriptFiles)
        assertEquals(1, preview.assetFiles)
        assertTrue(preview.hasScripts)
        assertEquals("Demo Skill", preview.entries.single().name)
        assertEquals("tester", preview.entries.single().author)
        assertEquals("2.0.0", preview.entries.single().version)
    }

    @Test
    fun `parseSkillArchive should ignore metadata files and flatten outer directory`() {
        val output = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("__MACOSX/"))
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("skills/demo/SKILL.md"))
                zip.write(buildSkillMarkdown("Demo", "Imported", "").toByteArray())
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("skills/demo/.DS_Store"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
        }
        val archiveBytes = output.toByteArray()

        val parsed = parseSkillArchive(ByteArrayInputStream(archiveBytes))

        assertEquals(listOf("demo/SKILL.md"), parsed.files.map { it.path })
        assertEquals(setOf("demo"), parsed.directories)
    }

    @Test
    fun `parseSkillArchive should reject oversized files`() {
        val output = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("demo/SKILL.md"))
            zip.write(buildSkillMarkdown("Demo", "Imported", "").toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("demo/references/huge.txt"))
            zip.write(ByteArray(600 * 1024))
            zip.closeEntry()
        }

        val result = runCatching {
            parseSkillArchive(ByteArrayInputStream(output.toByteArray()))
        }

        assertTrue(result.isFailure)
    }
}
