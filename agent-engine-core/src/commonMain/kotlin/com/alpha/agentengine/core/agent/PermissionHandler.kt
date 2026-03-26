package com.alpha.agentengine.core.agent

import com.alpha.agentengine.core.llm.ContentBlock

/**
 * 权限确认处理器。
 * App 层实现此接口来决定如何向用户请求确认（弹窗、通知等）。
 */
fun interface PermissionHandler {
    /**
     * 请求用户确认是否执行该 tool。
     * @return true 表示用户同意执行，false 表示拒绝
     */
    suspend fun requestConfirmation(toolCall: ContentBlock.ToolUse): Boolean
}

/**
 * 默认实现：自动同意所有操作（仅用于开发/测试）。
 */
object AutoApprovePermissionHandler : PermissionHandler {
    override suspend fun requestConfirmation(toolCall: ContentBlock.ToolUse): Boolean = true
}
