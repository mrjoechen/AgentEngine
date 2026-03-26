package com.alpha.agentengine.core.llm

import kotlinx.serialization.json.JsonObject

/**
 * 对话消息。
 */
data class Message(
    val role: Role,
    val content: List<ContentBlock>
) {
    companion object {
        fun user(text: String) = Message(Role.USER, listOf(ContentBlock.Text(text)))
        fun assistant(text: String) = Message(Role.ASSISTANT, listOf(ContentBlock.Text(text)))
        fun assistant(blocks: List<ContentBlock>) = Message(Role.ASSISTANT, blocks)
        fun toolResult(results: List<ToolCallResult>) = Message(
            Role.USER,
            results.map { ContentBlock.ToolResultBlock(it.toolUseId, it.result) }
        )
    }
}

enum class Role { USER, ASSISTANT }

/**
 * 消息内容块。
 */
sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ContentBlock()
    data class ToolResultBlock(
        val toolUseId: String,
        val result: String,
        val isError: Boolean = false
    ) : ContentBlock()
}

/**
 * 单个 Tool 调用及其结果。
 */
data class ToolCallResult(
    val toolUseId: String,
    val result: String,
    val isError: Boolean = false
)
