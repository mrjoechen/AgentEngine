package com.alpha.agentengine.core.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tool 参数定义。
 */
data class ParamDef(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
    val default: String? = null
) {
    /**
     * 转换为 JSON Schema property。
     */
    fun toJsonSchema(): JsonObject = buildJsonObject {
        val base = type.toJsonSchema()
        base.forEach { (k, v) -> put(k, v) }
        put("description", description)
        if (default != null) {
            put("default", default)
        }
    }
}
