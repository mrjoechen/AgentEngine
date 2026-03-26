package com.alpha.agentengine.core.tool

import kotlinx.serialization.json.JsonObject

/**
 * Tool 注册中心，管理所有已注册的 Tool。
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, ToolDefinition>()

    fun register(tool: ToolDefinition) {
        require(!tools.containsKey(tool.name)) {
            "Tool '${tool.name}' is already registered"
        }
        tools[tool.name] = tool
    }

    fun register(provider: ToolProvider) {
        provider.provideTools().forEach { register(it) }
    }

    fun registerAll(definitions: List<ToolDefinition>) {
        definitions.forEach { register(it) }
    }

    fun get(name: String): ToolDefinition? = tools[name]

    fun all(): List<ToolDefinition> = tools.values.toList()

    fun names(): Set<String> = tools.keys.toSet()

    fun size(): Int = tools.size

    fun contains(name: String): Boolean = tools.containsKey(name)

    /**
     * 执行指定 tool，自动进行参数包装和权限检查。
     */
    suspend fun execute(name: String, arguments: JsonObject): ToolResult {
        val tool = tools[name]
            ?: return ToolResult.error("Unknown tool: $name")

        if (tool.permission == Permission.BLOCKED) {
            return ToolResult.error("Tool '$name' is blocked")
        }

        return try {
            tool.handler.execute(ToolParams(arguments))
        } catch (e: Exception) {
            ToolResult.error("Tool '$name' execution failed: ${e.message}")
        }
    }

    /**
     * 生成所有 Tool 的 schema 列表（用于发送给 LLM）。
     */
    fun schemas(): List<ToolSchema> = tools.values.map { tool ->
        ToolSchema(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.toInputSchema()
        )
    }
}

/**
 * 发送给 LLM 的 Tool Schema。
 */
data class ToolSchema(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)
