package com.alpha.agentengine.core.tool

/**
 * Tool 执行权限级别。
 */
enum class Permission {
    /** 静默执行，无需用户确认（适合只读查询类操作） */
    ALLOWED,

    /** 需要用户确认后才能执行（适合写入/发送/支付类操作） */
    REQUIRES_CONFIRMATION,

    /** 禁止执行 */
    BLOCKED
}
