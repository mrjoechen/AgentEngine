package com.alpha.agentengine.core.tool

/**
 * Tool 提供者接口。
 * 业务模块实现此接口，批量提供 Tool 定义。
 */
interface ToolProvider {
    fun provideTools(): List<ToolDefinition>
}
