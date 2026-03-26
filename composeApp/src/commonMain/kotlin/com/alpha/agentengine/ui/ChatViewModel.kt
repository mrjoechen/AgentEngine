package com.alpha.agentengine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alpha.agentengine.core.AgentEngine
import com.alpha.agentengine.core.agent.AgentEvent
import com.alpha.agentengine.core.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(private val engine: AgentEngine) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(
            availableLlms = engine.availableLlms,
            currentLlm = engine.currentLlmName
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var session: Session = engine.createSession()

    /**
     * 切换 LLM connector，同时清空会话（不同模型上下文不互通）。
     */
    fun switchLlm(name: String) {
        engine.switchLlm(name)
        session = engine.createSession()
        _uiState.update {
            ChatUiState(
                availableLlms = engine.availableLlms,
                currentLlm = engine.currentLlmName
            )
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        _uiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(text, isUser = true),
                isLoading = true,
                inputText = ""
            )
        }

        viewModelScope.launch {
            val assistantMsg = StringBuilder()
            var currentToolName: String? = null

            engine.chatStream(session, text).collect { event ->
                when (event) {
                    is AgentEvent.TextDelta -> {
                        assistantMsg.append(event.text)
                        _uiState.update { state ->
                            val messages = state.messages.toMutableList()
                            val lastMsg = messages.lastOrNull()
                            if (lastMsg != null && !lastMsg.isUser && !lastMsg.isToolCall) {
                                messages[messages.lastIndex] = lastMsg.copy(text = assistantMsg.toString())
                            } else {
                                messages.add(ChatMessage(assistantMsg.toString(), isUser = false))
                            }
                            state.copy(messages = messages)
                        }
                    }

                    is AgentEvent.ToolCallStart -> {
                        currentToolName = event.toolName
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages + ChatMessage(
                                    text = "Tool: ${event.toolName}",
                                    isUser = false,
                                    isToolCall = true
                                )
                            )
                        }
                    }

                    is AgentEvent.ToolCallComplete -> {
                        _uiState.update { state ->
                            val messages = state.messages.toMutableList()
                            // Find the last tool call message that is still loading (not yet completed)
                            val toolMsgIndex = messages.indexOfLast {
                                it.isToolCall && !it.text.startsWith("[OK]") && !it.text.startsWith("[FAIL]")
                            }
                            if (toolMsgIndex >= 0) {
                                val status = if (event.isError) "[FAIL]" else "[OK]"
                                messages[toolMsgIndex] = messages[toolMsgIndex].copy(
                                    text = "$status ${event.toolName}: ${event.result.take(200)}"
                                )
                            } else {
                                // No pending tool call message found, append one
                                val status = if (event.isError) "[FAIL]" else "[OK]"
                                messages.add(
                                    ChatMessage(
                                        text = "$status ${event.toolName}: ${event.result.take(200)}",
                                        isUser = false,
                                        isToolCall = true
                                    )
                                )
                            }
                            state.copy(messages = messages)
                        }
                        // 重置 assistant 文本，准备接收下一轮 LLM 输出
                        assistantMsg.clear()
                    }

                    is AgentEvent.Done -> {
                        _uiState.update { state ->
                            state.copy(isLoading = false)
                        }
                    }

                    is AgentEvent.Error -> {
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages + ChatMessage(
                                    text = "Error: ${event.throwable.message}",
                                    isUser = false,
                                    isError = true
                                ),
                                isLoading = false
                            )
                        }
                    }

                    is AgentEvent.ConfirmationRequired -> {
                        event.onResult(true)
                    }
                }
            }
        }
    }

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun clearChat() {
        session.clear()
        _uiState.update {
            ChatUiState(
                availableLlms = engine.availableLlms,
                currentLlm = engine.currentLlmName
            )
        }
    }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val inputText: String = "",
    val availableLlms: List<String> = emptyList(),
    val currentLlm: String = ""
)

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isToolCall: Boolean = false,
    val isError: Boolean = false
)
