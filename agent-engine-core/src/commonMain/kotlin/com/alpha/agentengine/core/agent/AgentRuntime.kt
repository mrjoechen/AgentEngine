package com.alpha.agentengine.core.agent

import com.alpha.agentengine.core.llm.*
import com.alpha.agentengine.core.session.Session
import com.alpha.agentengine.core.tool.Permission
import com.alpha.agentengine.core.tool.ToolParams
import com.alpha.agentengine.core.tool.ToolRegistry
import com.alpha.agentengine.core.tool.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Agent 运行时：管理 User → LLM → ToolUse → Execute → LLM → ... → FinalText 的完整循环。
 *
 * @param connectorProvider 每次调用时获取当前 LLM connector（支持动态切换）
 */
class AgentRuntime(
    private val connectorProvider: () -> LlmConnector,
    private val toolRegistry: ToolRegistry,
    private val permissionHandler: PermissionHandler = AutoApprovePermissionHandler,
    private val maxToolRounds: Int = 20
) {
    /**
     * 兼容旧的直接传入 connector 的方式。
     */
    constructor(
        connector: LlmConnector,
        toolRegistry: ToolRegistry,
        permissionHandler: PermissionHandler = AutoApprovePermissionHandler,
        maxToolRounds: Int = 20
    ) : this(
        connectorProvider = { connector },
        toolRegistry = toolRegistry,
        permissionHandler = permissionHandler,
        maxToolRounds = maxToolRounds
    )
    /**
     * 执行完整的对话循环（非流式），直到 LLM 给出最终文本回复。
     */
    suspend fun run(
        session: Session,
        userMessage: String,
        eventCallback: (suspend (AgentEvent) -> Unit)? = null
    ): AgentResult {
        session.addUserMessage(userMessage)

        var rounds = 0
        var totalUsage = Usage(0, 0)

        while (rounds < maxToolRounds) {
            rounds++

            val response = try {
                connectorProvider().createMessage(
                    LlmRequest(
                        messages = session.messages,
                        tools = toolRegistry.schemas(),
                        systemPrompt = session.systemPrompt
                    )
                )
            } catch (e: Exception) {
                eventCallback?.invoke(AgentEvent.Error(e))
                return AgentResult.Failure(e)
            }

            // 累计 token 使用
            response.usage?.let { usage ->
                totalUsage = Usage(
                    inputTokens = totalUsage.inputTokens + usage.inputTokens,
                    outputTokens = totalUsage.outputTokens + usage.outputTokens
                )
            }

            if (!response.hasToolCalls()) {
                // 最终文本回复
                val text = response.text()
                session.addAssistantMessage(text)
                eventCallback?.invoke(AgentEvent.Done(text, totalUsage))
                return AgentResult.Success(text, totalUsage)
            }

            // 有 tool 调用
            session.addAssistantResponse(response)

            val toolResults = mutableListOf<ToolCallResult>()
            for (toolCall in response.toolCalls()) {
                eventCallback?.invoke(AgentEvent.ToolCallStart(toolCall.name, toolCall.id))

                val result = executeTool(toolCall, eventCallback)
                toolResults.add(result)

                eventCallback?.invoke(
                    AgentEvent.ToolCallComplete(
                        toolName = toolCall.name,
                        toolUseId = toolCall.id,
                        result = result.result,
                        isError = result.isError
                    )
                )
            }

            session.addToolResults(toolResults)
        }

        val error = IllegalStateException("Exceeded max tool rounds ($maxToolRounds)")
        eventCallback?.invoke(AgentEvent.Error(error))
        return AgentResult.Failure(error)
    }

    /**
     * 流式对话循环。返回 Flow<AgentEvent> 实时推送事件。
     * 自动处理 tool calling 循环，直到 LLM 给出最终文本回复。
     */
    fun runStream(session: Session, userMessage: String): Flow<AgentEvent> = channelFlow {
        session.addUserMessage(userMessage)

        var rounds = 0
        var totalUsage = Usage(0, 0)

        while (rounds < maxToolRounds) {
            rounds++

            val request = LlmRequest(
                messages = session.messages,
                tools = toolRegistry.schemas(),
                systemPrompt = session.systemPrompt
            )

            var streamResponse: LlmResponse? = null

            try {
                connectorProvider().createMessageStream(request).collect { event ->
                    when (event) {
                        is LlmStreamEvent.TextDelta -> send(AgentEvent.TextDelta(event.text))
                        is LlmStreamEvent.ToolUseStart -> send(AgentEvent.ToolCallStart(event.name, event.id))
                        is LlmStreamEvent.ToolUseInputDelta -> {} // 内部累积，不暴露
                        is LlmStreamEvent.MessageComplete -> streamResponse = event.response
                        is LlmStreamEvent.Error -> send(AgentEvent.Error(Exception(event.message)))
                    }
                }
            } catch (e: Exception) {
                send(AgentEvent.Error(e))
                return@channelFlow
            }

            val response = streamResponse ?: run {
                send(AgentEvent.Error(Exception("Stream ended without MessageComplete")))
                return@channelFlow
            }

            response.usage?.let { usage ->
                totalUsage = Usage(
                    inputTokens = totalUsage.inputTokens + usage.inputTokens,
                    outputTokens = totalUsage.outputTokens + usage.outputTokens
                )
            }

            if (!response.hasToolCalls()) {
                val text = response.text()
                session.addAssistantMessage(text)
                send(AgentEvent.Done(text, totalUsage))
                return@channelFlow
            }

            // 有 tool 调用 → 执行后继续循环
            session.addAssistantResponse(response)

            val toolResults = mutableListOf<ToolCallResult>()
            for (toolCall in response.toolCalls()) {
                val result = executeTool(toolCall, null)
                toolResults.add(result)
                send(
                    AgentEvent.ToolCallComplete(
                        toolName = toolCall.name,
                        toolUseId = toolCall.id,
                        result = result.result,
                        isError = result.isError
                    )
                )
            }
            session.addToolResults(toolResults)
            // 继续 while 循环，把结果送回 LLM
        }

        send(AgentEvent.Error(IllegalStateException("Exceeded max tool rounds ($maxToolRounds)")))
    }

    private suspend fun executeTool(
        toolCall: ContentBlock.ToolUse,
        eventCallback: (suspend (AgentEvent) -> Unit)?
    ): ToolCallResult {
        val tool = toolRegistry.get(toolCall.name)
            ?: return ToolCallResult(toolCall.id, "Unknown tool: ${toolCall.name}", isError = true)

        // 权限检查
        if (tool.permission == Permission.BLOCKED) {
            return ToolCallResult(toolCall.id, "Tool '${toolCall.name}' is blocked", isError = true)
        }

        if (tool.permission == Permission.REQUIRES_CONFIRMATION) {
            val approved = permissionHandler.requestConfirmation(toolCall)
            if (!approved) {
                return ToolCallResult(toolCall.id, "User denied execution of '${toolCall.name}'", isError = true)
            }
        }

        // 执行
        return try {
            val result = tool.handler.execute(ToolParams(toolCall.input))
            ToolCallResult(
                toolUseId = toolCall.id,
                result = result.toResultString(),
                isError = result is ToolResult.Error
            )
        } catch (e: Exception) {
            ToolCallResult(toolCall.id, "Tool execution failed: ${e.message}", isError = true)
        }
    }
}

/**
 * Agent 执行结果。
 */
sealed class AgentResult {
    data class Success(val text: String, val usage: Usage? = null) : AgentResult()
    data class Failure(val error: Throwable) : AgentResult()
}

/**
 * ToolResult 转为字符串（发送给 LLM）。
 */
private fun ToolResult.toResultString(): String = when (this) {
    is ToolResult.Text -> content
    is ToolResult.Json -> data.toString()
    is ToolResult.Image -> "[Image: $mimeType, ${base64.length} bytes]"
    is ToolResult.Error -> message
}
