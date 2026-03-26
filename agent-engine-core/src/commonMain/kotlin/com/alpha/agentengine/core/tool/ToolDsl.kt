package com.alpha.agentengine.core.tool

/**
 * DSL 构建单个 Tool 的 Builder。
 *
 * 用法:
 * ```
 * tool("search_contacts") {
 *     description = "搜索通讯录联系人"
 *     parameter("query", StringType, "搜索关键词")
 *     execute { params ->
 *         ToolResult.text(contactsRepo.search(params.getString("query")))
 *     }
 * }
 * ```
 */
@DslMarker
annotation class ToolDslMarker

@ToolDslMarker
class ToolBuilder(private val name: String) {
    var description: String = ""
    var permission: Permission = Permission.ALLOWED
    private val params = mutableListOf<ParamDef>()
    private var handler: ToolHandler? = null

    fun parameter(
        name: String,
        type: ParamType,
        description: String,
        required: Boolean = true,
        default: String? = null
    ) {
        params.add(ParamDef(name, type, description, required, default))
    }

    fun execute(block: suspend (ToolParams) -> ToolResult) {
        handler = ToolHandler { params -> block(params) }
    }

    fun build(): ToolDefinition {
        requireNotNull(handler) { "Tool '$name' must have an execute block" }
        require(description.isNotBlank()) { "Tool '$name' must have a description" }
        return ToolDefinition(
            name = name,
            description = description,
            parameters = params.toList(),
            permission = permission,
            handler = handler!!
        )
    }
}

/**
 * DSL 构建多个 Tool 的注册块。
 */
@ToolDslMarker
class ToolRegistryBuilder {
    private val tools = mutableListOf<ToolDefinition>()

    fun tool(name: String, block: ToolBuilder.() -> Unit) {
        tools.add(ToolBuilder(name).apply(block).build())
    }

    fun register(provider: ToolProvider) {
        tools.addAll(provider.provideTools())
    }

    fun register(definition: ToolDefinition) {
        tools.add(definition)
    }

    fun buildList(): List<ToolDefinition> = tools.toList()
}

/**
 * 顶层 DSL 入口：构建单个 Tool。
 */
fun tool(name: String, block: ToolBuilder.() -> Unit): ToolDefinition =
    ToolBuilder(name).apply(block).build()
