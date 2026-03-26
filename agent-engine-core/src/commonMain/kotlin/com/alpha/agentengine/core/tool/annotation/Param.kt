package com.alpha.agentengine.core.tool.annotation

/**
 * 标注 @AgentTool 函数的参数元信息。
 *
 * @param description 参数描述（传给 LLM）
 * @param required 是否必填（默认 true）
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class Param(
    val description: String,
    val required: Boolean = true
)
