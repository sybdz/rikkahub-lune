package me.rerere.rikkahub.data.skills

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val DEFAULT_SKILL_SCRIPT_TIMEOUT_MS = 30_000L

internal val AlwaysAllowedSkillRuntimeToolNames = setOf(
    "activate_skill",
    "read_skill_resource",
)

internal val SkillRuntimeToolNames = setOf(
    *AlwaysAllowedSkillRuntimeToolNames.toTypedArray(),
    "run_skill_script",
)

internal fun buildSkillRuntimeTools(
    skillsRepository: SkillsRepository,
    catalogEntries: Collection<SkillCatalogEntry>,
    resourceEntries: Collection<SkillCatalogEntry>,
    scriptEntries: Collection<SkillCatalogEntry>,
): List<Tool> {
    val catalogEntriesByDirectory = catalogEntries.associateBy { it.directoryName }
    val resourceEntriesByDirectory = resourceEntries.associateBy { it.directoryName }
    val scriptEntriesByDirectory = scriptEntries.associateBy { it.directoryName }
    if (catalogEntriesByDirectory.isEmpty() && resourceEntriesByDirectory.isEmpty() && scriptEntriesByDirectory.isEmpty()) {
        return emptyList()
    }

    return buildList {
        if (catalogEntriesByDirectory.isNotEmpty()) {
            add(Tool(
            name = "activate_skill",
            description = """
                Load one selected model-invocable skill package by directory name.
                Returns the skill metadata, full SKILL.md instructions, and available resource file paths.
                Use this when a listed skill seems relevant and you need its detailed instructions.
                Do not activate every skill preemptively.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("skill", buildJsonObject {
                            put("type", "string")
                            put("description", "The selected skill directory name to activate")
                        })
                    },
                    required = listOf("skill"),
                )
            },
            execute = execute@{ input ->
                val directoryName = input.jsonObject["skill"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: error("skill is required")
                val entry = catalogEntriesByDirectory[directoryName]
                    ?: error("Unknown selected skill: $directoryName")
                val activation = skillsRepository.loadSkillActivation(entry)
                val payload = buildJsonObject {
                    put("directory", entry.directoryName)
                    put("name", entry.name)
                    put("description", entry.description)
                    put("source", entry.sourceType.name.lowercase())
                    put("path", entry.path)
                    entry.version?.let { put("version", it) }
                    entry.author?.let { put("author", it) }
                    entry.license?.let { put("license", it) }
                    entry.compatibility?.let { put("compatibility", it) }
                    entry.allowedTools?.let { put("allowed_tools", it) }
                    entry.argumentHint?.let { put("argument_hint", it) }
                    put("user_invocable", entry.userInvocable)
                    put("model_invocable", entry.modelInvocable)
                    put("skill_markdown", activation.markdown.trim().truncateForSkillPrompt())
                    put(
                        "resource_files",
                        buildJsonArray {
                            activation.resourceFiles.forEach { resourcePath ->
                                add(resourcePath)
                            }
                        },
                    )
                }
                listOf(UIMessagePart.Text(payload.toString()))
            },
        ))
        }
        if (resourceEntriesByDirectory.isNotEmpty()) {
            add(Tool(
            name = "read_skill_resource",
            description = """
                Read one text resource file from a selected skill package by relative path.
                Use this after activate_skill or when you already know the exact resource path you need.
                This is for skill-owned resources only, not arbitrary workspace files.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("skill", buildJsonObject {
                            put("type", "string")
                            put("description", "The selected skill directory name")
                        })
                        put("resource_path", buildJsonObject {
                            put("type", "string")
                            put("description", "Relative path inside the skill package, for example references/example.md")
                        })
                        put("max_chars", buildJsonObject {
                            put("type", "integer")
                            put("description", "Optional maximum number of characters to return")
                        })
                    },
                    required = listOf("skill", "resource_path"),
                )
            },
            execute = execute@{ input ->
                val params = input.jsonObject
                val directoryName = params["skill"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: error("skill is required")
                val resourcePath = params["resource_path"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: error("resource_path is required")
                val entry = resourceEntriesByDirectory[directoryName]
                    ?: error("Unknown selected skill: $directoryName")
                val maxChars = params["max_chars"]?.jsonPrimitive?.intOrNull
                    ?: params["max_chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: SKILL_RESOURCE_DEFAULT_READ_CHAR_LIMIT
                val resource = skillsRepository.readSkillResource(
                    entry = entry,
                    relativePath = resourcePath,
                    maxChars = maxChars,
                )
                val payload = buildJsonObject {
                    put("directory", resource.entry.directoryName)
                    put("path", resource.relativePath)
                    put("truncated", resource.truncated)
                    put("total_bytes", resource.totalBytes)
                    put("content", resource.content)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            },
        ))
        }
        if (scriptEntriesByDirectory.isNotEmpty()) {
            add(Tool(
                name = "run_skill_script",
                description = """
                    Run one selected skill script from scripts/ by relative path.
                    Only use this when the skill instructions explicitly require a packaged script.
                    Supported scripts are .sh and .py files under scripts/.
                    Pass arguments as an array of plain strings.
                """.trimIndent().replace("\n", " "),
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("skill", buildJsonObject {
                                put("type", "string")
                                put("description", "The selected skill directory name")
                            })
                            put("script_path", buildJsonObject {
                                put("type", "string")
                                put("description", "Relative script path under scripts/, for example scripts/run.sh")
                            })
                            put("args", buildJsonObject {
                                put("type", "array")
                                put("description", "Optional script arguments")
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                            put("timeout_ms", buildJsonObject {
                                put("type", "integer")
                                put("description", "Optional timeout override for this script run")
                            })
                        },
                        required = listOf("skill", "script_path"),
                    )
                },
                execute = execute@{ input ->
                    val params = input.jsonObject
                    val directoryName = params["skill"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: error("skill is required")
                    val scriptPath = params["script_path"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: error("script_path is required")
                    val entry = scriptEntriesByDirectory[directoryName]
                        ?: error("Unknown selected skill: $directoryName")
                    val args = params["args"]?.let { value ->
                        value.jsonArray.map { item ->
                            item.jsonPrimitive.contentOrNull ?: error("args must contain strings only")
                        }
                    }.orEmpty()
                    val timeoutMs = params["timeout_ms"]?.jsonPrimitive?.intOrNull?.toLong()
                        ?: params["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?: DEFAULT_SKILL_SCRIPT_TIMEOUT_MS
                    val result = skillsRepository.runSkillEntryScript(
                        entry = entry,
                        relativePath = scriptPath,
                        args = args,
                        timeoutMs = timeoutMs,
                    )
                    val combinedOutput = listOfNotNull(
                        result.stdout.takeIf { it.isNotBlank() },
                        result.stderr.takeIf { it.isNotBlank() },
                        result.errMsg?.takeIf { it.isNotBlank() },
                    ).joinToString(separator = "\n")
                    val payload = buildJsonObject {
                        put("directory", result.entry.directoryName)
                        put("path", result.relativePath)
                        put("interpreter", result.interpreter)
                        put("exit_code", result.exitCode)
                        put("timed_out", result.timedOut)
                        put("output", combinedOutput)
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                },
            ))
        }
    }
}
