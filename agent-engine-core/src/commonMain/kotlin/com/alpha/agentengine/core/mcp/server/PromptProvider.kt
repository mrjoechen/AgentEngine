package com.alpha.agentengine.core.mcp.server

import com.alpha.agentengine.core.mcp.protocol.PromptGetResult
import com.alpha.agentengine.core.mcp.protocol.PromptInfo
import kotlinx.serialization.json.JsonObject

/**
 * MCP Prompt 提供者接口。
 * 实现此接口来暴露预定义的 Prompt 模板给 MCP Client。
 */
interface PromptProvider {
    fun listPrompts(): List<PromptInfo>
    suspend fun getPrompt(name: String, arguments: JsonObject?): PromptGetResult?
}
