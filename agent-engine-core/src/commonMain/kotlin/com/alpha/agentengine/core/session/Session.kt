package com.alpha.agentengine.core.session

import com.alpha.agentengine.core.llm.ContentBlock
import com.alpha.agentengine.core.llm.LlmResponse
import com.alpha.agentengine.core.llm.Message
import com.alpha.agentengine.core.llm.ToolCallResult
import com.benasher44.uuid.uuid4

/**
 * 对话会话，维护消息历史和上下文。
 */
class Session(
    val id: String = uuid4().toString(),
    val systemPrompt: String? = null,
    private val maxHistory: Int = 50
) {
    private val _messages = mutableListOf<Message>()
    val messages: List<Message> get() = _messages.toList()

    fun addUserMessage(text: String) {
        _messages.add(Message.user(text))
        trimHistory()
    }

    fun addAssistantMessage(text: String) {
        _messages.add(Message.assistant(text))
        trimHistory()
    }

    fun addAssistantResponse(response: LlmResponse) {
        _messages.add(Message.assistant(response.content))
        trimHistory()
    }

    fun addToolResults(results: List<ToolCallResult>) {
        _messages.add(Message.toolResult(results))
        trimHistory()
    }

    fun clear() {
        _messages.clear()
    }

    private fun trimHistory() {
        while (_messages.size > maxHistory) {
            _messages.removeFirst()
        }
    }
}
