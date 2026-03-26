package com.alpha.agentengine.demo

import com.alpha.agentengine.core.tool.*
import com.alpha.agentengine.core.tool.ParamType.*

/**
 * 示例 Tool 集合，用于 Demo 演示。
 */
class SampleToolProvider : ToolProvider {
    override fun provideTools(): List<ToolDefinition> = listOf(
        tool("get_current_time") {
            description = "获取当前日期和时间"
            execute {
                ToolResult.text(kotlin.time.Clock.System.now().toString())
            }
        },

        tool("calculate") {
            description = "计算数学表达式。支持加减乘除。"
            parameter("expression", StringType, "数学表达式，例如 '2 + 3 * 4'")
            execute { params ->
                val expr = params.getString("expression")
                try {
                    val result = evaluateSimpleExpression(expr)
                    ToolResult.text("$expr = $result")
                } catch (e: Exception) {
                    ToolResult.error("计算错误: ${e.message}")
                }
            }
        },

        tool("generate_random_number") {
            description = "生成指定范围内的随机整数"
            parameter("min", IntegerType, "最小值", required = false, default = "1")
            parameter("max", IntegerType, "最大值", required = false, default = "100")
            execute { params ->
                val min = params.getIntOrNull("min") ?: 1
                val max = params.getIntOrNull("max") ?: 100
                val result = (min..max).random()
                ToolResult.text("随机数: $result (范围 $min-$max)")
            }
        },

        tool("string_tools") {
            description = "字符串工具：统计字符数、单词数，或翻转字符串"
            parameter("text", StringType, "输入文本")
            parameter("operation", EnumType(listOf("count_chars", "count_words", "reverse")), "操作类型")
            execute { params ->
                val text = params.getString("text")
                val operation = params.getString("operation")
                val result = when (operation) {
                    "count_chars" -> "字符数: ${text.length}"
                    "count_words" -> "单词数: ${text.split("\\s+".toRegex()).filter { it.isNotBlank() }.size}"
                    "reverse" -> "翻转结果: ${text.reversed()}"
                    else -> "未知操作: $operation"
                }
                ToolResult.text(result)
            }
        },

        tool("todo_list") {
            description = "管理待办事项列表：添加、查看、完成、删除待办"
            parameter("action", EnumType(listOf("add", "list", "complete", "delete")), "操作类型")
            parameter("item", StringType, "待办内容（添加/完成/删除时使用）", required = false)
            execute { params ->
                val action = params.getString("action")
                val item = params.getStringOrNull("item")
                when (action) {
                    "add" -> {
                        if (item == null) return@execute ToolResult.error("请提供待办内容")
                        todos.add(TodoItem(item))
                        ToolResult.text("已添加待办: $item")
                    }
                    "list" -> {
                        if (todos.isEmpty()) {
                            ToolResult.text("待办列表为空")
                        } else {
                            val list = todos.mapIndexed { i, todo ->
                                val status = if (todo.done) "✅" else "⬜"
                                "${i + 1}. $status ${todo.text}"
                            }.joinToString("\n")
                            ToolResult.text("待办列表:\n$list")
                        }
                    }
                    "complete" -> {
                        val target = todos.find { it.text == item && !it.done }
                        if (target != null) {
                            target.done = true
                            ToolResult.text("已完成: ${target.text}")
                        } else {
                            ToolResult.error("未找到该待办项")
                        }
                    }
                    "delete" -> {
                        val removed = todos.removeAll { it.text == item }
                        if (removed) ToolResult.text("已删除: $item") else ToolResult.error("未找到该待办项")
                    }
                    else -> ToolResult.error("未知操作: $action")
                }
            }
        }
    )

    private val todos = mutableListOf<TodoItem>()

    private data class TodoItem(val text: String, var done: Boolean = false)
}

/**
 * 简易四则运算求值（支持 + - * /）
 */
private fun evaluateSimpleExpression(expr: String): Double {
    val parser = ExprParser(expr.replace(" ", ""))
    return parser.parseExpression()
}

private class ExprParser(private val tokens: String) {
    var pos = 0

    fun parseExpression(): Double {
        var left = parseTerm()
        while (pos < tokens.length && (tokens[pos] == '+' || tokens[pos] == '-')) {
            val op = tokens[pos++]
            val right = parseTerm()
            left = if (op == '+') left + right else left - right
        }
        return left
    }

    private fun parseTerm(): Double {
        var left = parseFactor()
        while (pos < tokens.length && (tokens[pos] == '*' || tokens[pos] == '/')) {
            val op = tokens[pos++]
            val right = parseFactor()
            left = if (op == '*') left * right else left / right
        }
        return left
    }

    private fun parseFactor(): Double {
        return if (pos < tokens.length && tokens[pos] == '(') {
            pos++
            val result = parseExpression()
            pos++
            result
        } else {
            parseNumber()
        }
    }

    private fun parseNumber(): Double {
        val start = pos
        if (pos < tokens.length && (tokens[pos] == '-' || tokens[pos] == '+')) pos++
        while (pos < tokens.length && (tokens[pos].isDigit() || tokens[pos] == '.')) pos++
        return tokens.substring(start, pos).toDouble()
    }
}
