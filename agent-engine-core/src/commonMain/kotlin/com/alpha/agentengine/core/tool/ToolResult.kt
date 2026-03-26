package com.alpha.agentengine.core.tool

import kotlinx.serialization.json.JsonObject

/**
 * Tool 执行结果。
 */
sealed class ToolResult {
    data class Text(val content: String) : ToolResult()
    data class Image(val base64: String, val mimeType: String) : ToolResult()
    data class Json(val data: JsonObject) : ToolResult()
    data class Error(val message: String, val isRetryable: Boolean = false) : ToolResult()

    companion object {
        fun text(content: String): ToolResult = Text(content)
        fun json(data: JsonObject): ToolResult = Json(data)
        fun image(base64: String, mimeType: String = "image/png"): ToolResult = Image(base64, mimeType)
        fun error(message: String, isRetryable: Boolean = false): ToolResult = Error(message, isRetryable)
    }
}
