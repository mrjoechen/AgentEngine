package com.alpha.agentengine.core.mcp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ========== Initialize ==========

@Serializable
data class InitializeParams(
    @SerialName("protocolVersion") val protocolVersion: String,
    val capabilities: ClientCapabilities,
    @SerialName("clientInfo") val clientInfo: Implementation
)

@Serializable
data class InitializeResult(
    @SerialName("protocolVersion") val protocolVersion: String,
    val capabilities: ServerCapabilities,
    @SerialName("serverInfo") val serverInfo: Implementation
)

@Serializable
data class ClientCapabilities(
    val roots: RootsCapability? = null,
    val sampling: JsonObject? = null
)

@Serializable
data class RootsCapability(
    val listChanged: Boolean? = null
)

@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability? = null,
    val resources: ResourcesCapability? = null,
    val prompts: PromptsCapability? = null
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean? = null
)

@Serializable
data class ResourcesCapability(
    val subscribe: Boolean? = null,
    val listChanged: Boolean? = null
)

@Serializable
data class PromptsCapability(
    val listChanged: Boolean? = null
)

@Serializable
data class Implementation(
    val name: String,
    val version: String
)

// ========== Tools ==========

@Serializable
data class McpToolInfo(
    val name: String,
    val description: String? = null,
    @SerialName("inputSchema") val inputSchema: JsonObject
)

@Serializable
data class ToolsListResult(
    val tools: List<McpToolInfo>
)

@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: JsonObject? = null
)

@Serializable
data class ToolCallResult(
    val content: List<McpContent>,
    @SerialName("isError") val isError: Boolean = false
)

@Serializable
data class McpContent(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    @SerialName("mimeType") val mimeType: String? = null
) {
    companion object {
        fun text(value: String) = McpContent(type = "text", text = value)
        fun image(base64: String, mimeType: String) =
            McpContent(type = "image", data = base64, mimeType = mimeType)
    }
}

// ========== Resources ==========

@Serializable
data class ResourceInfo(
    val uri: String,
    val name: String,
    val description: String? = null,
    @SerialName("mimeType") val mimeType: String? = null
)

@Serializable
data class ResourcesListResult(
    val resources: List<ResourceInfo>
)

@Serializable
data class ResourceReadParams(
    val uri: String
)

@Serializable
data class ResourceReadResult(
    val contents: List<ResourceContents>
)

@Serializable
data class ResourceContents(
    val uri: String,
    @SerialName("mimeType") val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null
)

// ========== Prompts ==========

@Serializable
data class PromptInfo(
    val name: String,
    val description: String? = null,
    val arguments: List<PromptArgument>? = null
)

@Serializable
data class PromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean? = null
)

@Serializable
data class PromptsListResult(
    val prompts: List<PromptInfo>
)

@Serializable
data class PromptGetParams(
    val name: String,
    val arguments: JsonObject? = null
)

@Serializable
data class PromptGetResult(
    val description: String? = null,
    val messages: List<PromptMessage>
)

@Serializable
data class PromptMessage(
    val role: String,
    val content: McpContent
)

// ========== Protocol Constants ==========

object McpProtocol {
    const val VERSION = "2024-11-05"

    object Methods {
        const val INITIALIZE = "initialize"
        const val INITIALIZED = "notifications/initialized"
        const val TOOLS_LIST = "tools/list"
        const val TOOLS_CALL = "tools/call"
        const val RESOURCES_LIST = "resources/list"
        const val RESOURCES_READ = "resources/read"
        const val PROMPTS_LIST = "prompts/list"
        const val PROMPTS_GET = "prompts/get"
        const val PING = "ping"
    }
}
