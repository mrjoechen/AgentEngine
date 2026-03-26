package com.alpha.agentengine.core.mcp.server

import com.alpha.agentengine.core.mcp.protocol.ResourceContents
import com.alpha.agentengine.core.mcp.protocol.ResourceInfo

/**
 * MCP Resource 提供者接口。
 * 实现此接口来暴露 App 中的资源（文件、数据等）给 MCP Client。
 */
interface ResourceProvider {
    fun listResources(): List<ResourceInfo>
    suspend fun readResource(uri: String): ResourceContents?
}
