package com.alpha.agentengine.core.agent

import com.alpha.agentengine.core.llm.ContentBlock
import com.alpha.agentengine.core.llm.Usage

/**
 * Agent 执行过程中产生的事件流。
 */
sealed class AgentEvent {
    /** 文本增量（流式输出） */
    data class TextDelta(val text: String) : AgentEvent()

    /** 开始调用 Tool */
    data class ToolCallStart(val toolName: String, val toolUseId: String) : AgentEvent()

    /** Tool 调用完成 */
    data class ToolCallComplete(
        val toolName: String,
        val toolUseId: String,
        val result: String,
        val isError: Boolean = false
    ) : AgentEvent()

    /** 需要用户确认 */
    data class ConfirmationRequired(
        val toolCall: ContentBlock.ToolUse,
        val onResult: suspend (Boolean) -> Unit
    ) : AgentEvent()

    /** 对话完成 */
    data class Done(val fullText: String, val usage: Usage? = null) : AgentEvent()

    /** 错误 */
    data class Error(val throwable: Throwable) : AgentEvent()
}
