package com.alpha.agentengine.llm.openai

import com.alpha.agentengine.core.llm.*
import com.alpha.agentengine.core.tool.ToolSchema
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

/**
 * OpenAI Chat Completions API 连接器。
 *
 * 兼容所有 OpenAI API 格式的服务（OpenAI、Azure OpenAI、各类代理/中转）。
 *
 * ```kotlin
 * val connector = OpenAiConnector(apiKey = "sk-...")
 * // 或指定自定义 baseUrl（兼容第三方中转）
 * val connector = OpenAiConnector(apiKey = "sk-...", baseUrl = "https://my-proxy.com/v1")
 * ```
 *
 * @param enableLogging 启用请求/响应日志输出（调试用）
 */
class OpenAiConnector(
    private val apiKey: String,
    override val modelId: String = "gpt-4o",
    private val baseUrl: String = "https://api.openai.com/v1",
    private val enableLogging: Boolean = true,
    httpClient: HttpClient? = null
) : LlmConnector {

    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val client = httpClient ?: HttpClient {
        install(ContentNegotiation) {
            json(this@OpenAiConnector.json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
        }
    }

    private fun log(tag: String, message: String) {
        if (enableLogging) {
            println("[OpenAiConnector/$tag] $message")
        }
    }

    // ========== 非流式 ==========

    override suspend fun createMessage(request: LlmRequest): LlmResponse {
        val openAiRequest = request.toOpenAiRequest(stream = false)
        val requestJson = json.encodeToString(OpenAiRequest.serializer(), openAiRequest)
        log("REQUEST", "POST $baseUrl/chat/completions")
        log("REQUEST", requestJson)

        val httpResponse = client.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(openAiRequest)
        }

        log("RESPONSE", "HTTP ${httpResponse.status.value}")

        if (!httpResponse.status.isSuccess()) {
            val errorText = httpResponse.bodyAsText()
            log("ERROR", errorText)
            val errorBody = try {
                json.decodeFromString(OpenAiErrorResponse.serializer(), errorText)
            } catch (_: Exception) { null }
            throw OpenAiApiException(
                statusCode = httpResponse.status.value,
                errorMessage = errorBody?.error?.message ?: "HTTP ${httpResponse.status.value}",
                errorType = errorBody?.error?.type ?: "unknown"
            )
        }

        val responseText = httpResponse.bodyAsText()
        log("RESPONSE", responseText)

        val response = json.decodeFromString(OpenAiResponse.serializer(), responseText)
        return response.toLlmResponse()
    }

    // ========== 流式 ==========

    override fun createMessageStream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val openAiRequest = request.toOpenAiRequest(stream = true)
        val requestBody = json.encodeToString(OpenAiRequest.serializer(), openAiRequest)
        log("STREAM_REQUEST", "POST $baseUrl/chat/completions (stream)")
        log("STREAM_REQUEST", requestBody)

        val accumulatedText = StringBuilder()
        // toolCallIndex → (id, name, argumentsBuffer)
        val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()
        var inputTokens = 0
        var outputTokens = 0
        var stopReason = StopReason.END_TURN

        client.preparePost("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(requestBody)
        }.execute { response ->
            log("STREAM_RESPONSE", "HTTP ${response.status.value}")
            val channel: ByteReadChannel = response.bodyAsChannel()
            val byteBuffer = ByteArray(8192)
            val lineBuffer = StringBuilder()

            while (!channel.isClosedForRead) {
                val bytesRead = try { channel.readAvailable(byteBuffer) } catch (_: Exception) { break }
                if (bytesRead <= 0) continue
                val chunk = byteBuffer.decodeToString(0, bytesRead)

                for (char in chunk) {
                    if (char == '\n') {
                        val line = lineBuffer.toString()
                        lineBuffer.clear()

                        if (line.startsWith("data: ")) {
                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") continue
                            if (data.isEmpty()) continue

                            log("STREAM_DATA", data)

                            val chunkResponse = try {
                                json.decodeFromString(OpenAiResponse.serializer(), data)
                            } catch (e: Exception) {
                                log("STREAM_PARSE_ERROR", "Failed to parse: ${e.message}")
                                continue
                            }

                            // usage 可能在最后一个 chunk 中（stream_options 开启时）
                            chunkResponse.usage?.let { usage ->
                                inputTokens = usage.promptTokens
                                outputTokens = usage.completionTokens
                            }

                            val choice = chunkResponse.choices.firstOrNull() ?: continue
                            val delta = choice.delta ?: continue

                            choice.finishReason?.let { reason ->
                                stopReason = parseFinishReason(reason)
                            }

                            // 文本增量
                            delta.content?.let { text ->
                                accumulatedText.append(text)
                                emit(LlmStreamEvent.TextDelta(text))
                            }

                            // tool_calls 增量
                            delta.toolCalls?.forEach { tc ->
                                val idx = tc.index ?: 0
                                val builder = toolCallBuilders.getOrPut(idx) { ToolCallBuilder() }

                                tc.id?.let { builder.id = it }
                                tc.function?.name?.let { name ->
                                    builder.name = name
                                    emit(LlmStreamEvent.ToolUseStart(
                                        id = builder.id ?: "call_$idx",
                                        name = name
                                    ))
                                }
                                tc.function?.arguments?.let { args ->
                                    builder.arguments.append(args)
                                    emit(LlmStreamEvent.ToolUseInputDelta(args))
                                }
                            }
                        }
                    } else if (char != '\r') {
                        lineBuffer.append(char)
                    }
                }
            }
        }

        // 构建最终 LlmResponse
        val finalBlocks = mutableListOf<ContentBlock>()
        if (accumulatedText.isNotEmpty()) {
            finalBlocks.add(ContentBlock.Text(accumulatedText.toString()))
        }
        for ((_, builder) in toolCallBuilders.entries.sortedBy { it.key }) {
            val input = try {
                json.decodeFromString(JsonObject.serializer(), builder.arguments.toString().ifEmpty { "{}" })
            } catch (_: Exception) {
                buildJsonObject {}
            }
            finalBlocks.add(ContentBlock.ToolUse(
                id = builder.id ?: "call_unknown",
                name = builder.name ?: "unknown",
                input = input
            ))
        }

        if (toolCallBuilders.isNotEmpty()) {
            stopReason = StopReason.TOOL_USE
        }

        emit(LlmStreamEvent.MessageComplete(
            LlmResponse(
                content = finalBlocks,
                stopReason = stopReason,
                usage = Usage(inputTokens, outputTokens)
            )
        ))
    }

    // ===== 内部转换 =====

    private fun LlmRequest.toOpenAiRequest(stream: Boolean): OpenAiRequest {
        val openAiMessages = mutableListOf<OpenAiMessage>()

        // system prompt
        systemPrompt?.let {
            openAiMessages.add(OpenAiMessage(role = "system", content = it))
        }

        // 消息转换
        for (msg in messages) {
            when {
                // assistant 含 tool_calls
                msg.role == Role.ASSISTANT && msg.content.any { it is ContentBlock.ToolUse } -> {
                    val textPart = msg.content.filterIsInstance<ContentBlock.Text>()
                        .joinToString("") { it.text }.ifEmpty { null }
                    val toolCalls = msg.content.filterIsInstance<ContentBlock.ToolUse>().map { tc ->
                        OpenAiToolCall(
                            id = tc.id,
                            type = "function",
                            function = OpenAiFunctionCall(
                                name = tc.name,
                                arguments = json.encodeToString(JsonObject.serializer(), tc.input)
                            )
                        )
                    }
                    openAiMessages.add(OpenAiMessage(
                        role = "assistant",
                        content = textPart,
                        toolCalls = toolCalls
                    ))
                }
                // tool result → 每个 result 是单独的 "tool" 消息
                msg.role == Role.USER && msg.content.all { it is ContentBlock.ToolResultBlock } -> {
                    for (block in msg.content.filterIsInstance<ContentBlock.ToolResultBlock>()) {
                        openAiMessages.add(OpenAiMessage(
                            role = "tool",
                            content = block.result,
                            toolCallId = block.toolUseId
                        ))
                    }
                }
                // 普通 user / assistant
                else -> {
                    val role = if (msg.role == Role.USER) "user" else "assistant"
                    val text = msg.content.filterIsInstance<ContentBlock.Text>()
                        .joinToString("") { it.text }
                    openAiMessages.add(OpenAiMessage(role = role, content = text))
                }
            }
        }

        val openAiTools = if (tools.isNotEmpty()) {
            tools.map { it.toOpenAiTool() }
        } else null

        return OpenAiRequest(
            model = modelId,
            messages = openAiMessages,
            tools = openAiTools,
            temperature = temperature,
            maxTokens = maxTokens,
            stream = if (stream) true else null
        )
    }

    private fun ToolSchema.toOpenAiTool(): OpenAiTool = OpenAiTool(
        type = "function",
        function = OpenAiFunction(
            name = name,
            description = description,
            parameters = inputSchema.takeIf { it.isNotEmpty() }
        )
    )

    private fun OpenAiResponse.toLlmResponse(): LlmResponse {
        val choice = choices.firstOrNull()
            ?: throw OpenAiApiException(0, "No choices in response", "NO_CHOICES")
        val message = choice.message
            ?: throw OpenAiApiException(0, "No message in choice", "NO_MESSAGE")

        val blocks = mutableListOf<ContentBlock>()

        message.content?.let { text ->
            if (text.isNotEmpty()) blocks.add(ContentBlock.Text(text))
        }

        message.toolCalls?.forEach { tc ->
            val input = try {
                json.decodeFromString(JsonObject.serializer(), tc.function?.arguments ?: "{}")
            } catch (_: Exception) {
                buildJsonObject {}
            }
            blocks.add(ContentBlock.ToolUse(
                id = tc.id ?: "call_unknown",
                name = tc.function?.name ?: "unknown",
                input = input
            ))
        }

        val hasToolCalls = blocks.any { it is ContentBlock.ToolUse }
        val stopReason = if (hasToolCalls) StopReason.TOOL_USE else parseFinishReason(choice.finishReason)

        return LlmResponse(
            content = blocks,
            stopReason = stopReason,
            usage = usage?.let { Usage(it.promptTokens, it.completionTokens) }
        )
    }

    private fun parseFinishReason(reason: String?): StopReason = when (reason) {
        "stop" -> StopReason.END_TURN
        "tool_calls" -> StopReason.TOOL_USE
        "length" -> StopReason.MAX_TOKENS
        else -> StopReason.END_TURN
    }
}

private class ToolCallBuilder {
    var id: String? = null
    var name: String? = null
    val arguments = StringBuilder()
}

class OpenAiApiException(
    val statusCode: Int,
    val errorMessage: String,
    val errorType: String
) : Exception("OpenAI API error ($statusCode/$errorType): $errorMessage")
