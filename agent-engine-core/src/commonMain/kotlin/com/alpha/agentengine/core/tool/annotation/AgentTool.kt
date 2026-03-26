package com.alpha.agentengine.core.tool.annotation

/**
 * 标注一个函数为 Agent Tool。
 * KSP 处理器会在编译期自动生成对应的 ToolProvider。
 *
 * 用法:
 * ```
 * class OrderService {
 *     @AgentTool(name = "search_orders", description = "搜索历史订单")
 *     suspend fun searchOrders(
 *         @Param("搜索关键词") query: String,
 *         @Param("状态筛选", required = false) status: String? = null
 *     ): String {
 *         return orderRepo.search(query, status)
 *     }
 * }
 * ```
 *
 * 生成的 ToolProvider:
 * ```
 * class OrderServiceToolProvider(private val instance: OrderService) : ToolProvider { ... }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class AgentTool(
    val name: String,
    val description: String,
    val permission: String = "ALLOWED"
)
