package com.alpha.agentengine.core

import com.alpha.agentengine.core.agent.*
import com.alpha.agentengine.core.llm.LlmConnector
import com.alpha.agentengine.core.session.Session
import com.alpha.agentengine.core.tool.ToolDefinition
import com.alpha.agentengine.core.tool.ToolProvider
import com.alpha.agentengine.core.tool.ToolRegistry
import com.alpha.agentengine.core.tool.ToolRegistryBuilder
import kotlinx.coroutines.flow.Flow
import kotlin.concurrent.Volatile

/**
 * AgentEngine 主入口。
 *
 * 支持配置多个 LLM connector，默认使用第一个，可动态切换。
 *
 * 用法:
 * ```
 * val engine = AgentEngine.builder()
 *     .llm(myConnector)                        // 单个 connector（向后兼容）
 *     .tools { ... }
 *     .build()
 *
 * // 或配置多个 connector
 * val engine = AgentEngine.builder()
 *     .llm("openai", openAiConnector)           // 第一个注册的为默认
 *     .llm("gemini", geminiConnector)
 *     .llm("anthropic", anthropicConnector)
 *     .tools { ... }
 *     .build()
 *
 * engine.switchLlm("gemini")                    // 动态切换
 * println(engine.currentLlmName)                // "gemini"
 * println(engine.availableLlms)                 // ["openai", "gemini", "anthropic"]
 * ```
 */
class AgentEngine internal constructor(
    private val connectors: LinkedHashMap<String, LlmConnector>,
    private val toolRegistry: ToolRegistry,
    private val permissionHandler: PermissionHandler,
    private val systemPrompt: String?,
    private val maxToolRounds: Int
) {
    @Volatile
    private var _currentLlmName: String = connectors.keys.first()

    private val runtime = AgentRuntime(
        connectorProvider = { connectors[_currentLlmName]!! },
        toolRegistry = toolRegistry,
        permissionHandler = permissionHandler,
        maxToolRounds = maxToolRounds
    )

    // ========== LLM 管理 ==========

    /**
     * 当前使用的 LLM connector 名称。
     */
    val currentLlmName: String get() = _currentLlmName

    /**
     * 当前使用的 LLM connector。
     */
    val currentLlm: LlmConnector get() = connectors[_currentLlmName]!!

    /**
     * 所有可用的 LLM connector 名称列表（按注册顺序）。
     */
    val availableLlms: List<String> get() = connectors.keys.toList()

    /**
     * 动态切换当前使用的 LLM connector。
     *
     * @param name 要切换到的 connector 名称
     * @throws IllegalArgumentException 如果指定名称不存在
     */
    fun switchLlm(name: String) {
        require(connectors.containsKey(name)) {
            "LLM connector '$name' not found. Available: ${connectors.keys.toList()}"
        }
        _currentLlmName = name
    }

    /**
     * 获取指定名称的 LLM connector。
     *
     * @return connector，不存在时返回 null
     */
    fun getLlm(name: String): LlmConnector? = connectors[name]

    /**
     * 动态添加一个新的 LLM connector（运行时注册）。
     */
    fun addLlm(name: String, connector: LlmConnector) {
        connectors[name] = connector
    }

    /**
     * 移除一个 LLM connector。不能移除当前正在使用的 connector。
     *
     * @throws IllegalStateException 如果尝试移除当前正在使用的 connector
     * @throws IllegalStateException 如果只剩一个 connector
     */
    fun removeLlm(name: String) {
        check(connectors.size > 1) {
            "Cannot remove the only LLM connector"
        }
        check(name != _currentLlmName) {
            "Cannot remove the currently active LLM connector '$name'. Switch to another one first."
        }
        connectors.remove(name)
    }

    // ========== 对话 ==========

    /**
     * 单轮对话（自动处理 tool calling 循环）。
     */
    suspend fun chat(message: String): AgentResult {
        val session = Session(systemPrompt = systemPrompt)
        return runtime.run(session, message)
    }

    /**
     * 单轮对话，带事件回调。
     */
    suspend fun chat(message: String, eventCallback: suspend (AgentEvent) -> Unit): AgentResult {
        val session = Session(systemPrompt = systemPrompt)
        return runtime.run(session, message, eventCallback)
    }

    /**
     * 基于已有 Session 对话（保持上下文）。
     */
    suspend fun chat(session: Session, message: String): AgentResult {
        return runtime.run(session, message)
    }

    /**
     * 基于已有 Session 对话，带事件回调。
     */
    suspend fun chat(
        session: Session,
        message: String,
        eventCallback: suspend (AgentEvent) -> Unit
    ): AgentResult {
        return runtime.run(session, message, eventCallback)
    }

    /**
     * 流式对话（新会话）。返回 Flow<AgentEvent> 实时推送文本增量和工具调用事件。
     */
    fun chatStream(message: String): Flow<AgentEvent> {
        val session = Session(systemPrompt = systemPrompt)
        return runtime.runStream(session, message)
    }

    /**
     * 流式对话（已有会话，保持上下文）。
     */
    fun chatStream(session: Session, message: String): Flow<AgentEvent> {
        return runtime.runStream(session, message)
    }

    /**
     * 创建新的会话。
     */
    fun createSession(customSystemPrompt: String? = null): Session {
        return Session(systemPrompt = customSystemPrompt ?: systemPrompt)
    }

    // ========== Tool 管理 ==========

    /**
     * 获取已注册的 Tool 列表。
     */
    fun registeredTools(): List<ToolDefinition> = toolRegistry.all()

    /**
     * 动态注册 Tool（运行时添加）。
     */
    fun registerTool(tool: ToolDefinition) {
        toolRegistry.register(tool)
    }

    /**
     * 动态注册 ToolProvider（运行时添加）。
     */
    fun registerProvider(provider: ToolProvider) {
        toolRegistry.register(provider)
    }

    companion object {
        fun builder(): Builder = Builder()
    }

    class Builder {
        private val connectors = LinkedHashMap<String, LlmConnector>()
        private var permissionHandler: PermissionHandler = AutoApprovePermissionHandler
        private var systemPrompt: String? = null
        private var maxToolRounds: Int = 20
        private val toolDefinitions = mutableListOf<ToolDefinition>()
        private val toolProviders = mutableListOf<ToolProvider>()

        /**
         * 注册一个 LLM connector（向后兼容）。
         * 使用 modelId 作为名称。多次调用可注册多个，第一个为默认。
         */
        fun llm(connector: LlmConnector) = apply {
            connectors[connector.modelId] = connector
        }

        /**
         * 注册一个具名 LLM connector。第一个注册的为默认。
         */
        fun llm(name: String, connector: LlmConnector) = apply {
            connectors[name] = connector
        }

        fun tools(block: ToolRegistryBuilder.() -> Unit) = apply {
            toolDefinitions.addAll(ToolRegistryBuilder().apply(block).buildList())
        }

        fun registerProvider(provider: ToolProvider) = apply {
            toolProviders.add(provider)
        }

        fun onConfirmationRequired(handler: PermissionHandler) = apply {
            this.permissionHandler = handler
        }

        fun systemPrompt(prompt: String) = apply {
            this.systemPrompt = prompt
        }

        fun maxToolRounds(max: Int) = apply {
            this.maxToolRounds = max
        }

        fun build(): AgentEngine {
            require(connectors.isNotEmpty()) {
                "At least one LlmConnector is required. Call .llm(connector) before .build()"
            }

            val registry = ToolRegistry()
            registry.registerAll(toolDefinitions)
            toolProviders.forEach { registry.register(it) }

            return AgentEngine(
                connectors = connectors,
                toolRegistry = registry,
                permissionHandler = permissionHandler,
                systemPrompt = systemPrompt,
                maxToolRounds = maxToolRounds
            )
        }
    }
}
