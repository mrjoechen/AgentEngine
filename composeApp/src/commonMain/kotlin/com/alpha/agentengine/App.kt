package com.alpha.agentengine

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.alpha.agentengine.core.AgentEngine
import com.alpha.agentengine.core.llm.*
import com.alpha.agentengine.demo.SampleToolProvider
import com.alpha.agentengine.llm.anthropic.AnthropicConnector
import com.alpha.agentengine.llm.gemini.GeminiConnector
import com.alpha.agentengine.llm.openai.OpenAiConnector
import com.alpha.agentengine.platform.PlatformToolProvider
import com.alpha.agentengine.ui.ChatScreen
import com.alpha.agentengine.ui.ChatViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Demo 用的模拟 LLM Connector。
 * 无需真实 API Key 即可体验 AgentEngine 的 Tool 调用流程。
 * 它会简单分析用户输入中的关键词，调用对应的 Tool。
 */
private class DemoLlmConnector : LlmConnector {
    override val modelId: String = "demo-mock"

    override suspend fun createMessage(request: LlmRequest): LlmResponse {
        val lastUserMsg = request.messages.lastOrNull { it.role == Role.USER }
        val lastContent = lastUserMsg?.content?.firstOrNull()

        // 检查是否是 tool result 回传 → 生成最终回复
        val hasToolResult = lastUserMsg?.content?.any { it is ContentBlock.ToolResultBlock } == true
        if (hasToolResult) {
            val toolResult = lastUserMsg?.content?.filterIsInstance<ContentBlock.ToolResultBlock>()?.firstOrNull()
            return LlmResponse(
                content = listOf(ContentBlock.Text(toolResult?.result ?: "操作完成")),
                stopReason = StopReason.END_TURN
            )
        }

        val userText = (lastContent as? ContentBlock.Text)?.text ?: ""
        val tools = request.tools

        // 简单关键词匹配，决定调用哪个 tool
        val toolCall = matchTool(userText, tools)

        return if (toolCall != null) {
            LlmResponse(
                content = listOf(toolCall),
                stopReason = StopReason.TOOL_USE
            )
        } else {
            LlmResponse(
                content = listOf(ContentBlock.Text("Hello! I am AgentEngine Demo. I can help you check the time, perform calculations, generate random numbers, process strings, or manage your to-do list. Please tell me what you would like to do？")),
                stopReason = StopReason.END_TURN
            )
        }
    }

    override fun createMessageStream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val response = createMessage(request)
        // 模拟流式输出
        for (block in response.content) {
            if (block is ContentBlock.Text) {
                for (char in block.text) {
                    emit(LlmStreamEvent.TextDelta(char.toString()))
                }
            }
        }
        emit(LlmStreamEvent.MessageComplete(response))
    }

    private fun matchTool(text: String, tools: List<com.alpha.agentengine.core.tool.ToolSchema>): ContentBlock.ToolUse? {
        val lower = text.lowercase()
        return when {
            lower.containsAny("时间", "几点", "日期", "time", "date") -> {
                ContentBlock.ToolUse(
                    id = "call_1",
                    name = "get_current_time",
                    input = kotlinx.serialization.json.buildJsonObject {}
                )
            }
            lower.containsAny("计算", "算", "加", "减", "乘", "除", "=", "+", "-", "*", "/") -> {
                val expr = text.replace(Regex("[计算帮我一下]"), "").trim()
                ContentBlock.ToolUse(
                    id = "call_2",
                    name = "calculate",
                    input = kotlinx.serialization.json.buildJsonObject {
                        put("expression", kotlinx.serialization.json.JsonPrimitive(expr.ifBlank { "1+1" }))
                    }
                )
            }
            lower.containsAny("随机", "random") -> {
                ContentBlock.ToolUse(
                    id = "call_3",
                    name = "generate_random_number",
                    input = kotlinx.serialization.json.buildJsonObject {}
                )
            }
            lower.containsAny("待办", "todo", "任务") -> {
                val action = when {
                    lower.containsAny("添加", "新增", "加", "add") -> "add"
                    lower.containsAny("完成", "done", "finish") -> "complete"
                    lower.containsAny("删除", "移除", "delete") -> "delete"
                    else -> "list"
                }
                val item = text.replace(Regex("(添加|新增|加一个|完成|删除|移除|查看|列出|待办|事项|todo|任务)"), "").trim()
                ContentBlock.ToolUse(
                    id = "call_5",
                    name = "todo_list",
                    input = kotlinx.serialization.json.buildJsonObject {
                        put("action", kotlinx.serialization.json.JsonPrimitive(action))
                        if (item.isNotBlank()) {
                            put("item", kotlinx.serialization.json.JsonPrimitive(item))
                        }
                    }
                )
            }
            else -> null
        }
    }
}

private fun String.containsAny(vararg keywords: String): Boolean =
    keywords.any { this.contains(it, ignoreCase = true) }

@Composable
fun App() {
    val engine = remember {
        AgentEngine.builder()
            .llm(DemoLlmConnector())
          .llm("Gemini", GeminiConnector(""))
          .llm("OpenAI", OpenAiConnector(apiKey = ""))
          .llm("Claude", AnthropicConnector(apiKey = ""))
          .systemPrompt("You are the AgentEngine Demo Assistant, capable of helping users check the time, perform calculations, generate random numbers, process strings, and manage to-do items.")
            .tools {
                register(PlatformToolProvider())
            }
            .build()
    }

    val viewModel = remember { ChatViewModel(engine) }

    AgentEngineTheme {
        ChatScreen(viewModel)
    }
}

// ==================== Theme ====================

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A5F),
    secondary = Color(0xFF7C3AED),
    onSecondary = Color.White,
    tertiary = Color(0xFF0891B2),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0F7FA),
    onTertiaryContainer = Color(0xFF164E63),
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurface = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF1E1B4B),
    tertiary = Color(0xFF22D3EE),
    onTertiary = Color(0xFF083344),
    tertiaryContainer = Color(0xFF164E63),
    onTertiaryContainer = Color(0xFFCFFAFE),
    background = Color(0xFF0F172A),
    surface = Color(0xFF1E293B),
    surfaceVariant = Color(0xFF334155),
    onSurface = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF475569),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA)
)

@Composable
private fun AgentEngineTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content
    )
}
