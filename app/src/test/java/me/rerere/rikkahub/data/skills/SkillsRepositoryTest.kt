package me.rerere.rikkahub.data.skills

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.ai.tools.termux.TermuxRunCommandRequest
import me.rerere.rikkahub.data.model.Assistant

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
    fun `shouldRefreshCatalogSnapshot should use ttl and workdir`() {
        val state = SkillsCatalogState(
            workdir = "/termux",
            rootPath = "/termux/skills",
            refreshedAt = 1_000L,
        )

        assertFalse(
            shouldRefreshCatalogSnapshot(
                state = state,
                workdir = "/termux",
                nowMs = 30_000L,
                ttlMs = 60_000L,
            )
        )
        assertTrue(
            shouldRefreshCatalogSnapshot(
                state = state,
                workdir = "/termux",
                nowMs = 80_000L,
                ttlMs = 60_000L,
            )
        )
        assertTrue(
            shouldRefreshCatalogSnapshot(
                state = state,
                workdir = "/other",
                nowMs = 2_000L,
                ttlMs = 60_000L,
            )
        )
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
    fun `bundled skill metadata should only backfill when installed contents still match bundled package`() {
        val bundledSkill = BundledSkill(
            directoryName = "skill-creator",
            assetPath = "builtin_skills/skill-creator",
        )
        val expectedMetadata = SkillSourceMetadata(
            sourceType = SkillSourceType.BUNDLED,
            sourceId = "skill-creator",
            hash = "new-hash",
        )

        assertTrue(
            shouldBackfillBundledSkillMetadata(
                installedMetadata = null,
                installedHash = "new-hash",
                expectedMetadata = expectedMetadata,
            )
        )
        assertFalse(
            shouldBackfillBundledSkillMetadata(
                installedMetadata = null,
                installedHash = "modified-hash",
                expectedMetadata = expectedMetadata,
            )
        )
        assertFalse(
            shouldRefreshBundledSkillInstallation(
                bundledSkill = bundledSkill,
                installedMetadata = null,
                installedHash = "new-hash",
                expectedMetadata = expectedMetadata,
            )
        )
        assertTrue(
            shouldRefreshBundledSkillInstallation(
                bundledSkill = bundledSkill,
                installedMetadata = expectedMetadata.copy(hash = "old-hash"),
                installedHash = "old-hash",
                expectedMetadata = expectedMetadata,
            )
        )
        assertFalse(
            shouldRefreshBundledSkillInstallation(
                bundledSkill = bundledSkill,
                installedMetadata = expectedMetadata,
                installedHash = "new-hash",
                expectedMetadata = expectedMetadata,
            )
        )
        assertFalse(
            shouldRefreshBundledSkillInstallation(
                bundledSkill = bundledSkill,
                installedMetadata = expectedMetadata.copy(sourceType = SkillSourceType.LOCAL),
                installedHash = "old-hash",
                expectedMetadata = expectedMetadata,
            )
        )
        assertTrue(
            shouldConvertBundledSkillInstallationToLocal(
                bundledSkill = bundledSkill,
                installedMetadata = expectedMetadata.copy(hash = "old-hash"),
                installedHash = "user-modified",
                expectedMetadata = expectedMetadata,
            )
        )
    }

    @Test
    fun `resolveUpdatedSkillSourceMetadata should reset bundled provenance after any edit`() {
        val resolved = resolveUpdatedSkillSourceMetadata(
            existingMetadata = SkillSourceMetadata(
                sourceType = SkillSourceType.BUNDLED,
                sourceId = "skill-creator",
                hash = "abc",
            ),
            originalDirectoryName = "skill-creator",
            finalDirectoryName = "skill-creator",
        )

        assertEquals(SkillSourceType.LOCAL, resolved.sourceType)
        assertEquals("skill-creator", resolved.sourceId)
        assertNull(resolved.hash)
    }

    @Test
    fun `resolveUpdatedSkillSourceMetadata should clear imported hash after local edits`() {
        val resolved = resolveUpdatedSkillSourceMetadata(
            existingMetadata = SkillSourceMetadata(
                sourceType = SkillSourceType.IMPORTED,
                sourceId = "demo-skill",
                hash = "abc123",
                version = "1.0.0",
            ),
            originalDirectoryName = "demo-skill",
            finalDirectoryName = "demo-skill",
        )

        assertEquals(SkillSourceType.IMPORTED, resolved.sourceType)
        assertEquals("demo-skill", resolved.sourceId)
        assertNull(resolved.hash)
        assertEquals("1.0.0", resolved.version)
    }

    @Test
    fun `normalizeCatalogSkillSourceMetadata should downgrade stale bundled metadata to local`() {
        val normalized = normalizeCatalogSkillSourceMetadata(
            directoryName = "skill-copy",
            metadata = SkillSourceMetadata(
                sourceType = SkillSourceType.BUNDLED,
                sourceId = "skill-creator",
                version = "1.0.0",
                hash = "abc",
            ),
        )

        assertEquals(SkillSourceType.LOCAL, normalized!!.sourceType)
        assertEquals("skill-copy", normalized.sourceId)
        assertNull(normalized.hash)
    }

    @Test
    fun `normalizeSkillFrontmatterExtras should keep at least one activation path`() {
        val normalized = normalizeSkillFrontmatterExtras(
            SkillFrontmatterExtras(
                userInvocable = false,
                disableModelInvocation = true,
            )
        )

        assertFalse(normalized.disableModelInvocation)
        assertTrue(normalized.hasActivationPath())
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
                userInvocable = true,
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
        assertTrue(parsed.frontmatter.userInvocable)
        assertFalse(parsed.frontmatter.modelInvocable)
        assertEquals("anthropic", parsed.frontmatter.author)
        assertEquals("1.2.3", parsed.frontmatter.version)
    }

    @Test
    fun `buildSkillMarkdown should reject skills without any activation path`() {
        val result = runCatching {
            buildSkillMarkdown(
                name = "Manual Trap",
                description = "Cannot be triggered",
                body = "",
                extras = SkillFrontmatterExtras(
                    userInvocable = false,
                    disableModelInvocation = true,
                ),
            )
        }

        assertTrue(result.isFailure)
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
        val entry = preview.entries.single()
        assertEquals("Demo Skill", entry.name)
        assertEquals("tester", entry.author)
        assertEquals("2.0.0", entry.version)
        assertEquals("demo-skill", entry.sourceId)
        assertNotNull(entry.packageHash)
        assertEquals(listOf("scripts/run.sh"), entry.scriptPaths)
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

    @Test
    fun `normalizeSkillArchiveEntryPath should reject absolute and deeply nested paths`() {
        assertTrue(runCatching { normalizeSkillArchiveEntryPath("/demo/SKILL.md") }.isFailure)
        assertTrue(runCatching { normalizeSkillArchiveEntryPath("C:/demo/SKILL.md") }.isFailure)
        assertTrue(
            runCatching {
                normalizeSkillArchiveEntryPath("a/b/c/d/e/f/g/h/i/SKILL.md")
            }.isFailure
        )
    }

    @Test
    fun `buildSkillImportPlan should sanitize imported top level directory names`() {
        val archive = ParsedSkillArchive(
            directories = linkedSetOf("My Skill", "My Skill/scripts"),
            files = listOf(
                SkillArchiveFile(
                    path = "My Skill/SKILL.md",
                    bytes = buildSkillMarkdown(
                        name = "My Skill",
                        description = "Imported",
                        body = "",
                    ).toByteArray(),
                ),
                SkillArchiveFile(
                    path = "My Skill/scripts/run.sh",
                    bytes = "echo ok".toByteArray(),
                ),
            ),
        )

        val plan = buildSkillImportPlan(archive = archive, suggestedDirectoryName = null)

        assertEquals(listOf("my-skill"), plan.topLevelDirectories)
        assertTrue(plan.files.any { it.path == "my-skill/SKILL.md" })
        assertTrue(plan.files.any { it.path == "my-skill/scripts/run.sh" })
    }

    @Test
    fun `buildSkillImportPlan should reject duplicate paths after remapping`() {
        val archive = ParsedSkillArchive(
            directories = linkedSetOf("demo"),
            files = listOf(
                SkillArchiveFile("demo/SKILL.md", buildSkillMarkdown("Demo", "Imported", "").toByteArray()),
                SkillArchiveFile("demo/SKILL.md", buildSkillMarkdown("Demo", "Shadow", "").toByteArray()),
            ),
        )

        val result = runCatching {
            buildSkillImportPlan(archive = archive, suggestedDirectoryName = null)
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `buildSkillImportPlan should reject archives without top level skill file`() {
        val archive = ParsedSkillArchive(
            directories = linkedSetOf("demo/resources"),
            files = listOf(
                SkillArchiveFile("demo/resources/readme.md", "hello".toByteArray()),
            ),
        )

        val result = runCatching {
            buildSkillImportPlan(archive = archive, suggestedDirectoryName = null)
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `requireTopLevelSkillDirectoryName should reject traversal and separators`() {
        assertTrue(runCatching { requireTopLevelSkillDirectoryName("../demo") }.isFailure)
        assertTrue(runCatching { requireTopLevelSkillDirectoryName("demo/test") }.isFailure)
        assertEquals("demo-skill", requireTopLevelSkillDirectoryName("demo-skill"))
    }

    @Test
    fun `normalizeSkillResourcePath should normalize nested resource paths`() {
        assertEquals(
            "references/guide.md",
            normalizeSkillResourcePath("references/guide.md"),
        )
        assertEquals(
            "scripts/run.sh",
            normalizeSkillResourcePath("scripts//run.sh"),
        )
    }

    @Test
    fun `normalizeSkillResourcePath should reject traversal reserved files and absolute paths`() {
        assertTrue(runCatching { normalizeSkillResourcePath("../guide.md") }.isFailure)
        assertTrue(runCatching { normalizeSkillResourcePath("/tmp/guide.md") }.isFailure)
        assertTrue(runCatching { normalizeSkillResourcePath("SKILL.md") }.isFailure)
        assertTrue(runCatching { normalizeSkillResourcePath(".rikkahub-skill-source.json") }.isFailure)
    }

    @Test
    fun `normalizeSkillScriptPath should require scripts directory and supported extensions`() {
        assertEquals("scripts/run.sh", normalizeSkillScriptPath("scripts/run.sh"))
        assertEquals("scripts/tool.py", normalizeSkillScriptPath("scripts/tool.py"))
        assertTrue(runCatching { normalizeSkillScriptPath("references/run.sh") }.isFailure)
        assertTrue(runCatching { normalizeSkillScriptPath("scripts/run.rb") }.isFailure)
    }

    @Test
    fun `normalizeSkillScriptArguments should reject control characters and oversize lists`() {
        assertEquals(listOf("--check", "demo"), normalizeSkillScriptArguments(listOf("--check", "demo")))
        assertTrue(runCatching { normalizeSkillScriptArguments(List(32) { "x" }) }.isFailure)
        assertTrue(runCatching { normalizeSkillScriptArguments(listOf("bad\narg")) }.isFailure)
    }

    @Test
    fun `assistant skill references should be renamed across all assistants`() {
        val assistants = listOf(
            Assistant(name = "A", selectedSkills = setOf("old-skill", "other")),
            Assistant(name = "B", selectedSkills = setOf("old-skill")),
            Assistant(name = "C", selectedSkills = emptySet()),
        )

        val renamed = assistants.replaceSelectedSkillDirectory(
            fromDirectoryName = "old-skill",
            toDirectoryName = "new-skill",
        )

        assertEquals(setOf("new-skill", "other"), renamed[0].selectedSkills)
        assertEquals(setOf("new-skill"), renamed[1].selectedSkills)
        assertTrue(renamed[2].selectedSkills.isEmpty())
    }

    @Test
    fun `assistant skill references should be removed across all assistants`() {
        val assistants = listOf(
            Assistant(name = "A", selectedSkills = setOf("old-skill", "other")),
            Assistant(name = "B", selectedSkills = setOf("old-skill")),
        )

        val updated = assistants.removeSelectedSkillDirectory("old-skill")

        assertEquals(setOf("other"), updated[0].selectedSkills)
        assertTrue(updated[1].selectedSkills.isEmpty())
    }
}
