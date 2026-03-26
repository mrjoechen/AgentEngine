package com.alpha.agentengine.llm.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ========== Request Models ==========

@Serializable
internal data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    val tools: List<AnthropicTool>? = null,
    val temperature: Double? = null,
    val stream: Boolean? = null
)

@Serializable
internal data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContentBlock>
)

@Serializable
internal sealed class AnthropicContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : AnthropicContentBlock()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject
    ) : AnthropicContentBlock()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean = false
    ) : AnthropicContentBlock()
}

@Serializable
internal data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject
)

// ========== Response Models ==========

@Serializable
internal data class AnthropicResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<AnthropicResponseContent>,
    @SerialName("stop_reason") val stopReason: String?,
    val usage: AnthropicUsage? = null
)

@Serializable
internal sealed class AnthropicResponseContent {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : AnthropicResponseContent()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject
    ) : AnthropicResponseContent()
}

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int
)

// ========== Error ==========

@Serializable
internal data class AnthropicErrorResponse(
    val type: String,
    val error: AnthropicErrorDetail
)

@Serializable
internal data class AnthropicErrorDetail(
    val type: String,
    val message: String
)
