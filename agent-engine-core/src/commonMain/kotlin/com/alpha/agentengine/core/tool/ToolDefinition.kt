package com.alpha.agentengine.core.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 一个完整的 Tool 定义，包含名称、描述、参数 schema、权限和执行逻辑。
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ParamDef> = emptyList(),
    val permission: Permission = Permission.ALLOWED,
    val handler: ToolHandler,
    /** 原始 JSON Schema（用于 MCP Client 远程 Tool，直接透传 schema） */
    val rawInputSchema: JsonObject? = null
) {
    /**
     * 生成符合 LLM function calling 规范的 JSON Schema。
     * 如果设置了 rawInputSchema（来自 MCP 远程 Tool），直接返回。
     */
    fun toInputSchema(): JsonObject = rawInputSchema ?: buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            parameters.forEach { param ->
                put(param.name, param.toJsonSchema())
            }
        })
        val requiredParams = parameters.filter { it.required }.map { it.name }
        if (requiredParams.isNotEmpty()) {
            putJsonArray("required") {
                requiredParams.forEach { add(JsonPrimitive(it)) }
            }
        }
    }

    companion object {
        /**
         * 从原始 JSON Schema 创建 Tool（用于 MCP Client 远程 Tool）。
         */
        fun fromRawSchema(
            name: String,
            description: String,
            inputSchema: JsonObject,
            permission: Permission = Permission.ALLOWED,
            handler: ToolHandler
        ) = ToolDefinition(
            name = name,
            description = description,
            rawInputSchema = inputSchema,
            permission = permission,
            handler = handler
        )
    }
}
