package com.alpha.agentengine.core.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Tool 参数类型，对应 JSON Schema 类型。
 */
sealed class ParamType(val typeName: String) {
    /** 字符串 */
    data object StringType : ParamType("string")

    /** 数字（整数或浮点） */
    data object NumberType : ParamType("number")

    /** 整数 */
    data object IntegerType : ParamType("integer")

    /** 布尔 */
    data object BooleanType : ParamType("boolean")

    /** 对象（嵌套参数） */
    data class ObjectType(val properties: List<ParamDef> = emptyList()) : ParamType("object")

    /** 数组 */
    data class ArrayType(val items: ParamType = StringType) : ParamType("array")

    /** 枚举（字符串枚举） */
    data class EnumType(val values: List<String>) : ParamType("string")

    /**
     * 转换为 JSON Schema 表示。
     */
    fun toJsonSchema(): JsonObject = buildJsonObject {
        put("type", typeName)
        when (this@ParamType) {
            is EnumType -> {
                putJsonArray("enum") {
                    values.forEach { add(JsonPrimitive(it)) }
                }
            }
            is ArrayType -> {
                put("items", items.toJsonSchema())
            }
            is ObjectType -> {
                put("properties", buildJsonObject {
                    properties.forEach { param ->
                        put(param.name, param.toJsonSchema())
                    }
                })
            }
            else -> {}
        }
    }
}
