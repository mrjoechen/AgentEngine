package com.alpha.agentengine.core.tool

/**
 * Tool 执行器。
 */
fun interface ToolHandler {
    suspend fun execute(params: ToolParams): ToolResult
}
