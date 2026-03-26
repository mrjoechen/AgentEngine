package com.alpha.agentengine.core.mcp.jsonrpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON-RPC 2.0 请求。
 */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonObject? = null,
    val id: JsonElement? = null
) {
    val isNotification: Boolean get() = id == null
}

/**
 * JSON-RPC 2.0 响应。
 */
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
    val id: JsonElement? = null
) {
    companion object {
        fun success(id: JsonElement?, result: JsonElement): JsonRpcResponse =
            JsonRpcResponse(result = result, id = id)

        fun error(id: JsonElement?, error: JsonRpcError): JsonRpcResponse =
            JsonRpcResponse(error = error, id = id)
    }
}

/**
 * JSON-RPC 2.0 错误。
 */
@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        fun parseError(msg: String = "Parse error") =
            JsonRpcError(PARSE_ERROR, msg)

        fun invalidRequest(msg: String = "Invalid request") =
            JsonRpcError(INVALID_REQUEST, msg)

        fun methodNotFound(method: String) =
            JsonRpcError(METHOD_NOT_FOUND, "Method not found: $method")

        fun invalidParams(msg: String) =
            JsonRpcError(INVALID_PARAMS, msg)

        fun internalError(msg: String) =
            JsonRpcError(INTERNAL_ERROR, msg)
    }
}
