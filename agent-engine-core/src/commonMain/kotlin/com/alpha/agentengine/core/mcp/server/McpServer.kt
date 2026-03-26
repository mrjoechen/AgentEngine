package com.alpha.agentengine.core.mcp.server

import com.alpha.agentengine.core.mcp.jsonrpc.JsonRpcDispatcher
import com.alpha.agentengine.core.mcp.jsonrpc.JsonRpcError
import com.alpha.agentengine.core.mcp.protocol.*
import com.alpha.agentengine.core.mcp.transport.McpTransport
import com.alpha.agentengine.core.tool.ToolRegistry
import com.alpha.agentengine.core.tool.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/**
 * MCP Server。
 * 将 ToolRegistry 中注册的 Tool 通过 MCP 协议暴露给外部 Client。
 *
 * 用法:
 * ```
 * val server = McpServer(
 *     name = "my-agent",
 *     version = "1.0.0",
 *     toolRegistry = registry,
 *     transport = stdioTransport
 * )
 * server.start(coroutineScope)
 * ```
 */
class McpServer(
    private val name: String,
    private val version: String,
    private val toolRegistry: ToolRegistry,
    private val transport: McpTransport,
    private val resourceProviders: List<ResourceProvider> = emptyList(),
    private val promptProviders: List<PromptProvider> = emptyList()
) {
    private val dispatcher = JsonRpcDispatcher()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
    private var job: Job? = null

    init {
        registerHandlers()
    }

    /**
     * 启动 MCP Server，开始监听传入的消息。
     */
    fun start(scope: CoroutineScope): Job {
        val serverJob = scope.launch {
            transport.start()
            transport.incoming().collect { message ->
                val response = dispatcher.dispatch(message)
                if (response != null) {
                    transport.send(response)
                }
            }
        }
        job = serverJob
        return serverJob
    }

    /**
     * 停止 MCP Server。
     */
    suspend fun stop() {
        job?.cancel()
        transport.close()
    }

    private fun registerHandlers() {
        // initialize
        dispatcher.registerMethod(McpProtocol.Methods.INITIALIZE) { params ->
            val capabilities = ServerCapabilities(
                tools = if (toolRegistry.size() > 0) ToolsCapability() else null,
                resources = if (resourceProviders.isNotEmpty()) ResourcesCapability() else null,
                prompts = if (promptProviders.isNotEmpty()) PromptsCapability() else null
            )
            val result = InitializeResult(
                protocolVersion = McpProtocol.VERSION,
                capabilities = capabilities,
                serverInfo = Implementation(name = name, version = version)
            )
            json.encodeToJsonElement(InitializeResult.serializer(), result)
        }

        // notifications/initialized
        dispatcher.registerNotification(McpProtocol.Methods.INITIALIZED) { }

        // ping
        dispatcher.registerMethod(McpProtocol.Methods.PING) {
            JsonObject(emptyMap())
        }

        // tools/list
        dispatcher.registerMethod(McpProtocol.Methods.TOOLS_LIST) {
            val tools = toolRegistry.all().map { tool ->
                McpToolInfo(
                    name = tool.name,
                    description = tool.description,
                    inputSchema = tool.toInputSchema()
                )
            }
            json.encodeToJsonElement(ToolsListResult.serializer(), ToolsListResult(tools))
        }

        // tools/call
        dispatcher.registerMethod(McpProtocol.Methods.TOOLS_CALL) { params ->
            if (params == null) {
                throw JsonRpcException(JsonRpcError.invalidParams("Missing params"))
            }

            val callParams = json.decodeFromJsonElement(ToolCallParams.serializer(), params)
            val arguments = callParams.arguments ?: JsonObject(emptyMap())
            val toolResult = toolRegistry.execute(callParams.name, arguments)

            val mcpResult = when (toolResult) {
                is ToolResult.Text -> ToolCallResult(
                    content = listOf(McpContent.text(toolResult.content))
                )
                is ToolResult.Json -> ToolCallResult(
                    content = listOf(McpContent.text(toolResult.data.toString()))
                )
                is ToolResult.Image -> ToolCallResult(
                    content = listOf(McpContent.image(toolResult.base64, toolResult.mimeType))
                )
                is ToolResult.Error -> ToolCallResult(
                    content = listOf(McpContent.text(toolResult.message)),
                    isError = true
                )
            }
            json.encodeToJsonElement(ToolCallResult.serializer(), mcpResult)
        }

        // resources/list
        dispatcher.registerMethod(McpProtocol.Methods.RESOURCES_LIST) {
            val resources = resourceProviders.flatMap { it.listResources() }
            json.encodeToJsonElement(ResourcesListResult.serializer(), ResourcesListResult(resources))
        }

        // resources/read
        dispatcher.registerMethod(McpProtocol.Methods.RESOURCES_READ) { params ->
            if (params == null) {
                throw JsonRpcException(JsonRpcError.invalidParams("Missing params"))
            }
            val readParams = json.decodeFromJsonElement(ResourceReadParams.serializer(), params)
            val contents = resourceProviders.firstNotNullOfOrNull { it.readResource(readParams.uri) }
                ?: throw JsonRpcException(JsonRpcError.invalidParams("Resource not found: ${readParams.uri}"))
            json.encodeToJsonElement(ResourceReadResult.serializer(), ResourceReadResult(listOf(contents)))
        }

        // prompts/list
        dispatcher.registerMethod(McpProtocol.Methods.PROMPTS_LIST) {
            val prompts = promptProviders.flatMap { it.listPrompts() }
            json.encodeToJsonElement(PromptsListResult.serializer(), PromptsListResult(prompts))
        }

        // prompts/get
        dispatcher.registerMethod(McpProtocol.Methods.PROMPTS_GET) { params ->
            if (params == null) {
                throw JsonRpcException(JsonRpcError.invalidParams("Missing params"))
            }
            val getParams = json.decodeFromJsonElement(PromptGetParams.serializer(), params)
            val result = promptProviders.firstNotNullOfOrNull {
                it.getPrompt(getParams.name, getParams.arguments)
            } ?: throw JsonRpcException(JsonRpcError.invalidParams("Prompt not found: ${getParams.name}"))
            json.encodeToJsonElement(PromptGetResult.serializer(), result)
        }
    }

    companion object {
        fun builder() = Builder()
    }

    class Builder {
        private var name: String = "AgentEngine"
        private var version: String = "1.0.0"
        private var toolRegistry: ToolRegistry? = null
        private var transport: McpTransport? = null
        private val resourceProviders = mutableListOf<ResourceProvider>()
        private val promptProviders = mutableListOf<PromptProvider>()

        fun name(name: String) = apply { this.name = name }
        fun version(version: String) = apply { this.version = version }
        fun toolRegistry(registry: ToolRegistry) = apply { this.toolRegistry = registry }
        fun transport(transport: McpTransport) = apply { this.transport = transport }
        fun addResourceProvider(provider: ResourceProvider) = apply { resourceProviders.add(provider) }
        fun addPromptProvider(provider: PromptProvider) = apply { promptProviders.add(provider) }

        fun build(): McpServer {
            return McpServer(
                name = name,
                version = version,
                toolRegistry = requireNotNull(toolRegistry) { "ToolRegistry is required" },
                transport = requireNotNull(transport) { "McpTransport is required" },
                resourceProviders = resourceProviders.toList(),
                promptProviders = promptProviders.toList()
            )
        }
    }
}

internal class JsonRpcException(val error: JsonRpcError) : Exception(error.message)
