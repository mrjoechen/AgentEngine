package com.alpha.agentengine.core.mcp.jsonrpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * JSON-RPC 方法分发器。
 * 将 JSON-RPC 请求路由到对应的处理函数。
 */
class JsonRpcDispatcher {
    private val handlers = mutableMapOf<String, suspend (JsonObject?) -> JsonElement>()
    private val notificationHandlers = mutableMapOf<String, suspend (JsonObject?) -> Unit>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun registerMethod(method: String, handler: suspend (JsonObject?) -> JsonElement) {
        handlers[method] = handler
    }

    fun registerNotification(method: String, handler: suspend (JsonObject?) -> Unit) {
        notificationHandlers[method] = handler
    }

    /**
     * 处理原始 JSON 字符串，返回响应字符串（通知返回 null）。
     */
    suspend fun dispatch(rawMessage: String): String? {
        val request = try {
            json.decodeFromString<JsonRpcRequest>(rawMessage)
        } catch (e: Exception) {
            val response = JsonRpcResponse.error(null, JsonRpcError.parseError(e.message ?: "Parse error"))
            return json.encodeToString(JsonRpcResponse.serializer(), response)
        }

        // 通知（无 id）
        if (request.isNotification) {
            notificationHandlers[request.method]?.invoke(request.params)
            return null
        }

        // 请求（有 id，需要响应）
        val handler = handlers[request.method]
        if (handler == null) {
            val response = JsonRpcResponse.error(request.id, JsonRpcError.methodNotFound(request.method))
            return json.encodeToString(JsonRpcResponse.serializer(), response)
        }

        return try {
            val result = handler(request.params)
            val response = JsonRpcResponse.success(request.id, result)
            json.encodeToString(JsonRpcResponse.serializer(), response)
        } catch (e: Exception) {
            val response = JsonRpcResponse.error(
                request.id,
                JsonRpcError.internalError(e.message ?: "Internal error")
            )
            json.encodeToString(JsonRpcResponse.serializer(), response)
        }
    }

    /**
     * 编码 JSON-RPC 请求为字符串（用于 Client 发送）。
     */
    fun encodeRequest(request: JsonRpcRequest): String =
        json.encodeToString(JsonRpcRequest.serializer(), request)

    /**
     * 解码 JSON-RPC 响应。
     */
    fun decodeResponse(raw: String): JsonRpcResponse =
        json.decodeFromString(JsonRpcResponse.serializer(), raw)
}
