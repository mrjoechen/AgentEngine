package com.alpha.agentengine.core.llm

import com.alpha.agentengine.core.tool.ToolSchema
import kotlinx.coroutines.flow.Flow

/**
 * LLM 连接器抽象。不绑定特定模型提供商。
 */
interface LlmConnector {
    /** 模型标识，例如 "claude-sonnet-4-6" */
    val modelId: String

    /** 单轮调用（含 tool definitions） */
    suspend fun createMessage(request: LlmRequest): LlmResponse

    /** 流式调用 */
    fun createMessageStream(request: LlmRequest): Flow<LlmStreamEvent>
}

/**
 * LLM 请求。
 */
data class LlmRequest(
    val messages: List<Message>,
    val tools: List<ToolSchema> = emptyList(),
    val systemPrompt: String? = null,
    val maxTokens: Int = 4096,
    val temperature: Double? = null
)

/**
 * LLM 响应。
 */
data class LlmResponse(
    val content: List<ContentBlock>,
    val stopReason: StopReason,
    val usage: Usage? = null
) {
    /** 提取纯文本内容 */
    fun text(): String = content.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }

    /** 提取 tool_use 调用 */
    fun toolCalls(): List<ContentBlock.ToolUse> = content.filterIsInstance<ContentBlock.ToolUse>()

    /** 是否包含 tool 调用 */
    fun hasToolCalls(): Boolean = content.any { it is ContentBlock.ToolUse }
}

enum class StopReason {
    END_TURN,
    TOOL_USE,
    MAX_TOKENS,
    STOP_SEQUENCE
}

data class Usage(
    val inputTokens: Int,
    val outputTokens: Int
)

/**
 * 流式事件。
 */
sealed class LlmStreamEvent {
    data class TextDelta(val text: String) : LlmStreamEvent()
    data class ToolUseStart(val id: String, val name: String) : LlmStreamEvent()
    data class ToolUseInputDelta(val delta: String) : LlmStreamEvent()
    data class MessageComplete(val response: LlmResponse) : LlmStreamEvent()
    data class Error(val message: String) : LlmStreamEvent()
}
