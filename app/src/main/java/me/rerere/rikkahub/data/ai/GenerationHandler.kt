package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.tools.termux.TermuxApprovalBlacklistMatcher
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.skills.SkillCatalogEntry
import me.rerere.rikkahub.data.skills.SkillsRepository
import me.rerere.rikkahub.data.skills.SkillToolPolicy
import me.rerere.rikkahub.data.skills.buildActivatedSkillsPrompt
import me.rerere.rikkahub.data.skills.buildSkillRuntimeTools
import me.rerere.rikkahub.data.skills.buildSkillsCatalogPrompt
import me.rerere.rikkahub.data.skills.resolveExplicitSkillInvocations
import me.rerere.rikkahub.data.skills.resolveSelectedSkillEntries
import me.rerere.rikkahub.data.skills.resolveSkillToolPolicy
import me.rerere.rikkahub.data.skills.resolveToolActivatedSkillInvocations
import me.rerere.rikkahub.data.skills.shouldInjectSkillsCatalog
import me.rerere.rikkahub.data.skills.shouldLoadExplicitSkillActivations
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock

private const val TAG = "GenerationHandler"

internal data class ToolApprovalPassResult(
    val tools: List<UIMessagePart.Tool>,
    val hasPendingApproval: Boolean,
)

private data class SkillRequestContext(
    val catalogPrompt: String? = null,
    val activatedPrompt: String? = null,
    val toolPolicy: SkillToolPolicy = SkillToolPolicy(),
    val catalogToolEntries: List<SkillCatalogEntry> = emptyList(),
    val resourceToolEntries: List<SkillCatalogEntry> = emptyList(),
    val scriptToolEntries: List<SkillCatalogEntry> = emptyList(),
)

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
    private val skillsRepository: SkillsRepository,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val skillRequestContext = resolveSkillRequestContext(
                assistant = assistant,
                settings = settings,
                model = model,
                messages = messages,
            )
            val skillRuntimeTools = buildSkillRuntimeTools(
                skillsRepository = skillsRepository,
                catalogEntries = skillRequestContext.catalogToolEntries,
                resourceEntries = skillRequestContext.resourceToolEntries,
                scriptEntries = skillRequestContext.scriptToolEntries,
            )
            val allToolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (assistant.enableMemory) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        onCreation = { content ->
                            memoryRepo.addMemory(memoryAssistantId, content)
                        },
                        onUpdate = { id, content ->
                            memoryRepo.updateContent(id, content)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        }
                    ).let(this::addAll)
                }
                addAll(skillRuntimeTools)
                addAll(tools)
            }
            val generationTools = skillRequestContext.toolPolicy.filterVisibleTools(allToolsInternal)

            // Check if we have approved tool calls to execute (resuming after approval)
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                !it.isExecuted && (it.approvalState is ToolApprovalState.Approved || it.approvalState is ToolApprovalState.Denied || it.approvalState is ToolApprovalState.Answered)
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = generationTools,
                    memories = memories ?: emptyList(),
                    skillRequestContext = skillRequestContext,
                    stream = assistant.streamOutput
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                val blacklistRules = TermuxApprovalBlacklistMatcher.parseBlacklistRules(
                    settings.termuxApprovalBlacklist
                )
                val approvalPass = evaluatePendingToolApprovals(
                    tools = tools,
                    toolsInternal = generationTools,
                    blacklistRules = blacklistRules,
                )
                val updatedTools = approvalPass.tools

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (approvalPass.hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after approval - use the pending tools directly
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} approved/denied tools")
                toolsToProcess = messages.last().getTools().filter {
                    !it.isExecuted && (it.approvalState is ToolApprovalState.Approved || it.approvalState is ToolApprovalState.Denied || it.approvalState is ToolApprovalState.Answered)
                }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        runCatching {
                            val toolDef = allToolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            skillRequestContext.toolPolicy.validate(
                                toolName = tool.toolName,
                                input = args,
                            )?.let { violation ->
                                return@runCatching listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put("error", JsonPrimitive(violation.message))
                                            }
                                        )
                                    )
                                )
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            toolDef.execute(args)
                        }.onSuccess { result ->
                            executedTools += tool.copy(output = result)
                        }.onFailure {
                            it.printStackTrace()
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(buildString {
                                                        append("[${it.javaClass.name}] ${it.message}")
                                                        append("\n${it.stackTraceToString()}")
                                                    })
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        skillRequestContext: SkillRequestContext,
        stream: Boolean
    ) {
        val internalMessages = buildList {
            val system = buildString {
                // 如果助手有系统提示，则添加到消息中
                if (assistant.systemPrompt.isNotBlank()) {
                    append(assistant.systemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
                }
                if (assistant.enableRecentChatsReference) {
                    appendLine()
                    append(buildRecentChatsPrompt(assistant, conversationRepo))
                }

                skillRequestContext.catalogPrompt?.let {
                    appendLine()
                    append(it)
                }
                skillRequestContext.activatedPrompt?.let {
                    appendLine()
                    append(it)
                }

                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(messages.limitContext(assistant.contextMessageSize))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings
        )

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            thinkingBudget = assistant.thinkingBudget,
            openAIReasoningEffort = assistant.openAIReasoningEffort,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = messages,
                    providerSetting = provider,
                    stream = true
                )
            )
            providerImpl.streamText(
                providerSetting = provider,
                messages = internalMessages,
                params = params
            ).collect {
                messages = messages.handleMessageChunk(chunk = it, model = model)
                it.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(usage = message.usage.merge(usage))
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(messages)
            }
        } else {
            aiLoggingManager.addLog(
                AILogging.Generation(
                    params = params,
                    messages = messages,
                    providerSetting = provider,
                    stream = false
                )
            )
            val chunk = providerImpl.generateText(
                providerSetting = provider,
                messages = internalMessages,
                params = params,
            )
            messages = messages.handleMessageChunk(chunk = chunk, model = model)
            chunk.usage?.let { usage ->
                messages = messages.mapIndexed { index, message ->
                    if (index == messages.lastIndex) {
                        message.copy(
                            usage = message.usage.merge(usage)
                        )
                    } else {
                        message
                    }
                }
            }
            onUpdateMessages(messages)
        }
    }

    private suspend fun resolveSkillRequestContext(
        assistant: Assistant,
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
    ): SkillRequestContext {
        if (!assistant.skillsEnabled || assistant.selectedSkills.isEmpty()) {
            return SkillRequestContext()
        }

        val skillsCatalog = skillsRepository.getCatalogSnapshot(
            workdir = settings.termuxWorkdir,
        )
        val selectedEntries = resolveSelectedSkillEntries(
            selectedSkills = assistant.selectedSkills,
            availableSkills = skillsCatalog.entries,
        )
        val modelInvocableEntries = selectedEntries.filter { it.modelInvocable }
        val explicitSkillInvocations = if (shouldLoadExplicitSkillActivations(assistant)) {
            resolveExplicitSkillInvocations(
                messages = messages,
                availableSkills = selectedEntries,
            )
        } else {
            emptyList()
        }
        val toolActivatedEntries = resolveToolActivatedSkillInvocations(
            messages = messages,
            availableSkills = selectedEntries,
        )
        val activatedEntries = (explicitSkillInvocations + toolActivatedEntries)
            .distinctBy { it.directoryName }
        val activations = if (activatedEntries.isEmpty()) {
            emptyList()
        } else {
            skillsRepository.loadSkillActivations(activatedEntries.map { it.directoryName })
        }
        val catalogToolEntries = if (shouldInjectSkillsCatalog(assistant, model)) {
            modelInvocableEntries
        } else {
            emptyList()
        }
        val resourceToolEntries = activations.map { it.entry }
            .distinctBy { it.directoryName }
        val scriptToolEntries = if (
            assistant.skillsEnabled &&
            assistant.skillsScriptExecutionEnabled &&
            model.abilities.contains(ModelAbility.TOOL)
        ) {
            activations.map { it.entry }.distinctBy { it.directoryName }
        } else {
            emptyList()
        }

        return SkillRequestContext(
            catalogPrompt = buildSkillsCatalogPrompt(
                assistant = assistant,
                model = model,
                catalog = skillsCatalog,
            ),
            activatedPrompt = buildActivatedSkillsPrompt(activations),
            toolPolicy = resolveSkillToolPolicy(activations),
            catalogToolEntries = catalogToolEntries,
            resourceToolEntries = resourceToolEntries,
            scriptToolEntries = scriptToolEntries,
        )
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}

internal fun evaluatePendingToolApprovals(
    tools: List<UIMessagePart.Tool>,
    toolsInternal: List<Tool>,
    blacklistRules: List<String>,
): ToolApprovalPassResult {
    var hasPendingApproval = false
    var approvalGateLocked = false

    val updatedTools = tools.map { tool ->
        if (approvalGateLocked) {
            return@map tool
        }

        val toolDef = toolsInternal.find { it.name == tool.toolName }
        val forceApproval = TermuxApprovalBlacklistMatcher.shouldForceApproval(
            tool = tool,
            blacklistRules = blacklistRules,
        )

        when {
            (toolDef?.needsApproval == true || forceApproval) &&
                tool.approvalState is ToolApprovalState.Auto -> {
                hasPendingApproval = true
                approvalGateLocked = true
                tool.copy(approvalState = ToolApprovalState.Pending)
            }

            tool.approvalState is ToolApprovalState.Pending -> {
                hasPendingApproval = true
                approvalGateLocked = true
                tool
            }

            else -> tool
        }
    }

    return ToolApprovalPassResult(
        tools = updatedTools,
        hasPendingApproval = hasPendingApproval,
    )
}
