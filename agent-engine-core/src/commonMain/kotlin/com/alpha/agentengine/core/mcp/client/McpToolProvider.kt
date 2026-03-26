package com.alpha.agentengine.core.mcp.client

import com.alpha.agentengine.core.tool.ToolDefinition
import com.alpha.agentengine.core.tool.ToolHandler
import com.alpha.agentengine.core.tool.ToolProvider
import com.alpha.agentengine.core.tool.ToolResult

/**
 * 将远程 MCP Server 的 Tool 桥接到本地 ToolRegistry。
 *
 * 用法:
 * ```
 * val client = McpClient(transport)
 * client.connect(scope)
 * val provider = McpToolProvider(client)
 * provider.refresh()  // 拉取远程 tool 列表
 * engine.registerProvider(provider)
 * ```
 */
class McpToolProvider(
    private val client: McpClient,
    private val toolNamePrefix: String = ""
) : ToolProvider {

    private var cachedTools: List<ToolDefinition> = emptyList()

    /**
     * 从远程 MCP Server 刷新 Tool 列表。
     * 必须在 provideTools() 之前调用至少一次。
     */
    suspend fun refresh() {
        val remoteTools = client.listTools()
        cachedTools = remoteTools.map { mcpTool ->
            val toolName = if (toolNamePrefix.isNotEmpty()) {
                "${toolNamePrefix}/${mcpTool.name}"
            } else {
                mcpTool.name
            }

            ToolDefinition.fromRawSchema(
                name = toolName,
                description = mcpTool.description ?: "",
                inputSchema = mcpTool.inputSchema,
                handler = ToolHandler { params ->
                    try {
                        client.callTool(mcpTool.name, params.raw)
                    } catch (e: Exception) {
                        ToolResult.error("Remote tool error: ${e.message}")
                    }
                }
            )
        }
    }

    override fun provideTools(): List<ToolDefinition> = cachedTools
}
