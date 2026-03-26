package com.alpha.agentengine.llm.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ========== Request Models ==========
// OpenAI Chat Completions API 使用 snake_case

@Serializable
internal data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val tools: List<OpenAiTool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean? = null
)

@Serializable
internal data class OpenAiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
internal data class OpenAiTool(
    val type: String,
    val function: OpenAiFunction
)

@Serializable
internal data class OpenAiFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject? = null
)

@Serializable
internal data class OpenAiToolCall(
    val id: String,
    val type: String,
    val function: OpenAiFunctionCall
)

@Serializable
internal data class OpenAiFunctionCall(
    val name: String,
    val arguments: String
)

// ========== Response Models ==========

@Serializable
internal data class OpenAiResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage? = null
)

@Serializable
internal data class OpenAiChoice(
    val index: Int = 0,
    val message: OpenAiResponseMessage? = null,
    val delta: OpenAiResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
internal data class OpenAiResponseMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiStreamToolCall>? = null
)

@Serializable
internal data class OpenAiStreamToolCall(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiStreamFunctionCall? = null
)

@Serializable
internal data class OpenAiStreamFunctionCall(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
internal data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
)

// ========== Error ==========

@Serializable
internal data class OpenAiErrorResponse(
    val error: OpenAiErrorDetail
)

@Serializable
internal data class OpenAiErrorDetail(
    val message: String = "",
    val type: String = "",
    val code: String? = null
)
