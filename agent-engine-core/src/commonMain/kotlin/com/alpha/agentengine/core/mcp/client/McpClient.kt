package com.alpha.agentengine.core.mcp.client

import com.alpha.agentengine.core.mcp.jsonrpc.JsonRpcRequest
import com.alpha.agentengine.core.mcp.jsonrpc.JsonRpcResponse
import com.alpha.agentengine.core.mcp.protocol.*
import com.alpha.agentengine.core.mcp.transport.McpTransport
import com.alpha.agentengine.core.tool.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * MCP Client。
 * 连接到外部 MCP Server，调用远程工具。
 *
 * 用法:
 * ```
 * val client = McpClient(
 *     transport = stdioTransport,
 *     clientName = "my-app",
 *     clientVersion = "1.0"
 * )
 * client.connect(scope)
 * val tools = client.listTools()
 * val result = client.callTool("search", buildJsonObject { put("q", "hello") })
 * client.close()
 * ```
 */
class McpClient(
    private val transport: McpTransport,
    private val clientName: String = "AgentEngine",
    private val clientVersion: String = "1.0.0"
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private var requestIdCounter = 0
    private val pendingRequests = mutableMapOf<String, CompletableDeferred<JsonRpcResponse>>()
    private val mutex = Mutex()
    private var receiveJob: Job? = null

    var serverInfo: Implementation? = null
        private set
    var serverCapabilities: ServerCapabilities? = null
        private set

    /**
     * 连接并初始化。
     */
    suspend fun connect(scope: CoroutineScope) {
        transport.start()

        // 启动消息接收循环
        receiveJob = scope.launch {
            transport.incoming().collect { message ->
                handleIncoming(message)
            }
        }

        // 发送 initialize
        val initParams = InitializeParams(
            protocolVersion = McpProtocol.VERSION,
            capabilities = ClientCapabilities(),
            clientInfo = Implementation(clientName, clientVersion)
        )
        val result = sendRequest(
            McpProtocol.Methods.INITIALIZE,
            json.encodeToJsonElement(InitializeParams.serializer(), initParams).jsonObject
        )

        val initResult = json.decodeFromJsonElement(InitializeResult.serializer(), result)
        serverInfo = initResult.serverInfo
        serverCapabilities = initResult.capabilities

        // 发送 initialized 通知
        sendNotification(McpProtocol.Methods.INITIALIZED)
    }

    /**
     * 列出远程 Server 的所有 Tool。
     */
    suspend fun listTools(): List<McpToolInfo> {
        val result = sendRequest(McpProtocol.Methods.TOOLS_LIST, null)
        val listResult = json.decodeFromJsonElement(ToolsListResult.serializer(), result)
        return listResult.tools
    }

    /**
     * 调用远程 Tool。
     */
    suspend fun callTool(name: String, arguments: JsonObject = JsonObject(emptyMap())): ToolResult {
        val params = json.encodeToJsonElement(
            ToolCallParams.serializer(),
            ToolCallParams(name = name, arguments = arguments)
        ).jsonObject

        val result = sendRequest(McpProtocol.Methods.TOOLS_CALL, params)
        val callResult = json.decodeFromJsonElement(ToolCallResult.serializer(), result)

        // 转换 MCP 结果为 ToolResult
        val content = callResult.content.firstOrNull()
        return if (callResult.isError) {
            ToolResult.error(content?.text ?: "Unknown error")
        } else {
            when (content?.type) {
                "image" -> ToolResult.image(content.data ?: "", content.mimeType ?: "image/png")
                else -> ToolResult.text(content?.text ?: "")
            }
        }
    }

    /**
     * 关闭连接。
     */
    suspend fun close() {
        receiveJob?.cancel()
        transport.close()
    }

    // ===== 内部 =====

    private suspend fun sendRequest(method: String, params: JsonObject?): JsonElement {
        val id = mutex.withLock { (++requestIdCounter).toString() }
        val deferred = CompletableDeferred<JsonRpcResponse>()

        mutex.withLock {
            pendingRequests[id] = deferred
        }

        val request = JsonRpcRequest(
            method = method,
            params = params,
            id = JsonPrimitive(id)
        )
        transport.send(json.encodeToString(JsonRpcRequest.serializer(), request))

        val response = deferred.await()

        if (response.error != null) {
            throw McpClientException(
                code = response.error.code,
                errorMessage = response.error.message
            )
        }

        return response.result ?: JsonObject(emptyMap())
    }

    private suspend fun sendNotification(method: String, params: JsonObject? = null) {
        val request = JsonRpcRequest(
            method = method,
            params = params,
            id = null // notification
        )
        transport.send(json.encodeToString(JsonRpcRequest.serializer(), request))
    }

    private suspend fun handleIncoming(message: String) {
        val response = try {
            json.decodeFromString(JsonRpcResponse.serializer(), message)
        } catch (_: Exception) {
            return // 忽略无法解析的消息（可能是通知）
        }

        val id = (response.id as? JsonPrimitive)?.content ?: return

        val deferred = mutex.withLock {
            pendingRequests.remove(id)
        }
        deferred?.complete(response)
    }
}

class McpClientException(
    val code: Int,
    val errorMessage: String
) : Exception("MCP error ($code): $errorMessage")
