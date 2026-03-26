package com.alpha.agentengine.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Tool 执行时收到的参数封装，提供类型安全的取值方法。
 */
class ToolParams(val raw: JsonObject) {

    fun getString(name: String): String =
        raw[name]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing required param: $name")

    fun getStringOrNull(name: String): String? =
        raw[name]?.jsonPrimitive?.content

    fun getInt(name: String): Int =
        raw[name]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("Missing or invalid int param: $name")

    fun getIntOrNull(name: String): Int? =
        raw[name]?.jsonPrimitive?.intOrNull

    fun getLong(name: String): Long =
        raw[name]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("Missing or invalid long param: $name")

    fun getDouble(name: String): Double =
        raw[name]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("Missing or invalid double param: $name")

    fun getBoolean(name: String): Boolean =
        raw[name]?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("Missing or invalid boolean param: $name")

    fun getBooleanOrNull(name: String): Boolean? =
        raw[name]?.jsonPrimitive?.booleanOrNull

    fun getObject(name: String): JsonObject =
        raw[name]?.jsonObject
            ?: throw IllegalArgumentException("Missing or invalid object param: $name")

    fun getArray(name: String): JsonArray =
        raw[name]?.jsonArray
            ?: throw IllegalArgumentException("Missing or invalid array param: $name")

    fun has(name: String): Boolean = raw.containsKey(name)

    override fun toString(): String = raw.toString()
}
