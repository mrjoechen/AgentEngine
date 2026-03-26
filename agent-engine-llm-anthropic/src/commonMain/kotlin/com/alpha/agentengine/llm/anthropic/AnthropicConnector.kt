package com.alpha.agentengine.llm.anthropic

import com.alpha.agentengine.core.llm.*
import com.alpha.agentengine.core.tool.ToolSchema
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Anthropic Claude API 连接器。
 *
 * 用法:
 * ```
 * val connector = AnthropicConnector(apiKey = "sk-ant-...")
 * ```
 *
 * @param enableLogging 启用请求/响应日志输出（调试用）
 */
class AnthropicConnector(
    private val apiKey: String,
    override val modelId: String = "claude-sonnet-4-6",
    private val baseUrl: String = "https://api.anthropic.com",
    private val apiVersion: String = "2023-06-01",
    private val enableLogging: Boolean = false,
    httpClient: HttpClient? = null
) : LlmConnector {

    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        classDiscriminator = "type"
    }

    private val client = httpClient ?: HttpClient {
        install(ContentNegotiation) {
            json(this@AnthropicConnector.json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
        }
        install(SSE)
    }

    private fun log(tag: String, message: String) {
        if (enableLogging) {
            println("[AnthropicConnector/$tag] $message")
        }
    }

    override suspend fun createMessage(request: LlmRequest): LlmResponse {
        val anthropicRequest = request.toAnthropicRequest(stream = false)

        val requestJson = json.encodeToString(AnthropicRequest.serializer(), anthropicRequest)
        log("REQUEST", "POST $baseUrl/v1/messages")
        log("REQUEST", requestJson)

        val httpResponse = client.post("$baseUrl/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", apiVersion)
            setBody(anthropicRequest)
        }

        log("RESPONSE", "HTTP ${httpResponse.status.value}")

        if (!httpResponse.status.isSuccess()) {
            val errorText = httpResponse.bodyAsText()
            log("ERROR", errorText)
            val errorBody = try {
                json.decodeFromString(AnthropicErrorResponse.serializer(), errorText)
            } catch (_: Exception) {
                null
            }
            throw AnthropicApiException(
                statusCode = httpResponse.status.value,
                errorType = errorBody?.error?.type ?: "unknown",
                errorMessage = errorBody?.error?.message ?: "HTTP ${httpResponse.status.value}"
            )
        }

        val responseText = httpResponse.bodyAsText()
        log("RESPONSE", responseText)

        val response = json.decodeFromString(AnthropicResponse.serializer(), responseText)
        return response.toLlmResponse()
    }

    override fun createMessageStream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val anthropicRequest = request.toAnthropicRequest(stream = true)
        val requestBody = json.encodeToString(AnthropicRequest.serializer(), anthropicRequest)

        log("STREAM_REQUEST", "POST $baseUrl/v1/messages (stream)")
        log("STREAM_REQUEST", requestBody)

        // 流式状态跟踪
        val contentBlocks = mutableListOf<ContentBlockState>()
        var inputTokens = 0
        var outputTokens = 0
        var stopReason = StopReason.END_TURN

        client.sse(
            urlString = "$baseUrl/v1/messages",
            request = {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", apiVersion)
                setBody(requestBody)
            }
        ) {
            incoming.collect { sseEvent ->
                val eventType = sseEvent.event ?: return@collect
                val data = sseEvent.data ?: return@collect

                log("STREAM_EVENT", "[$eventType] $data")

                when (eventType) {
                    "message_start" -> {
                        val msg = json.decodeFromString(StreamMessageStart.serializer(), data)
                        msg.message.usage?.let { inputTokens = it.inputTokens }
                    }

                    "content_block_start" -> {
                        val block = json.decodeFromString(StreamContentBlockStart.serializer(), data)
                        when (val cb = block.contentBlock) {
                            is StreamContentBlock.Text -> {
                                contentBlocks.add(ContentBlockState.TextBlock(StringBuilder()))
                            }
                            is StreamContentBlock.ToolUse -> {
                                contentBlocks.add(
                                    ContentBlockState.ToolUseBlock(
                                        id = cb.id,
                                        name = cb.name,
                                        inputJson = StringBuilder()
                                    )
                                )
                                emit(LlmStreamEvent.ToolUseStart(cb.id, cb.name))
                            }
                        }
                    }

                    "content_block_delta" -> {
                        val delta = json.decodeFromString(StreamContentBlockDelta.serializer(), data)
                        val blockState = contentBlocks.getOrNull(delta.index) ?: return@collect

                        when (val d = delta.delta) {
                            is StreamDelta.TextDelta -> {
                                if (blockState is ContentBlockState.TextBlock) {
                                    blockState.text.append(d.text)
                                }
                                emit(LlmStreamEvent.TextDelta(d.text))
                            }
                            is StreamDelta.InputJsonDelta -> {
                                if (blockState is ContentBlockState.ToolUseBlock) {
                                    blockState.inputJson.append(d.partialJson)
                                }
                                emit(LlmStreamEvent.ToolUseInputDelta(d.partialJson))
                            }
                        }
                    }

                    "content_block_stop" -> {
                        // 不需要额外处理，块内容已在 delta 中累积
                    }

                    "message_delta" -> {
                        val msgDelta = json.decodeFromString(StreamMessageDelta.serializer(), data)
                        msgDelta.delta.stopReason?.let { reason ->
                            stopReason = parseStopReason(reason)
                        }
                        msgDelta.usage?.let { outputTokens = it.outputTokens }
                    }

                    "message_stop" -> {
                        // 构建最终 LlmResponse
                        val finalBlocks = contentBlocks.map { state ->
                            when (state) {
                                is ContentBlockState.TextBlock ->
                                    ContentBlock.Text(state.text.toString())
                                is ContentBlockState.ToolUseBlock -> {
                                    val inputObj = try {
                                        json.decodeFromString(
                                            JsonObject.serializer(),
                                            state.inputJson.toString().ifEmpty { "{}" }
                                        )
                                    } catch (_: Exception) {
                                        buildJsonObject {}
                                    }
                                    ContentBlock.ToolUse(state.id, state.name, inputObj)
                                }
                            }
                        }

                        val response = LlmResponse(
                            content = finalBlocks,
                            stopReason = stopReason,
                            usage = Usage(inputTokens, outputTokens)
                        )
                        log("STREAM_COMPLETE", "stopReason=$stopReason, blocks=${finalBlocks.size}, usage=Usage($inputTokens, $outputTokens)")
                        emit(LlmStreamEvent.MessageComplete(response))
                    }

                    "error" -> {
                        log("STREAM_ERROR", data)
                        emit(LlmStreamEvent.Error("Stream error: $data"))
                    }
                }
            }
        }
    }

    // ===== 内部转换 =====

    private fun LlmRequest.toAnthropicRequest(stream: Boolean = false): AnthropicRequest {
        return AnthropicRequest(
            model = modelId,
            maxTokens = maxTokens,
            messages = messages.map { it.toAnthropicMessage() },
            system = systemPrompt,
            tools = if (tools.isNotEmpty()) tools.map { it.toAnthropicTool() } else null,
            temperature = temperature,
            stream = if (stream) true else null
        )
    }

    private fun Message.toAnthropicMessage(): AnthropicMessage {
        return AnthropicMessage(
            role = when (role) {
                Role.USER -> "user"
                Role.ASSISTANT -> "assistant"
            },
            content = content.map { it.toAnthropicBlock() }
        )
    }

    private fun ContentBlock.toAnthropicBlock(): AnthropicContentBlock = when (this) {
        is ContentBlock.Text -> AnthropicContentBlock.Text(text)
        is ContentBlock.ToolUse -> AnthropicContentBlock.ToolUse(id, name, input)
        is ContentBlock.ToolResultBlock -> AnthropicContentBlock.ToolResult(
            toolUseId = toolUseId,
            content = result,
            isError = isError
        )
    }

    private fun ToolSchema.toAnthropicTool(): AnthropicTool = AnthropicTool(
        name = name,
        description = description,
        inputSchema = inputSchema
    )

    private fun AnthropicResponse.toLlmResponse(): LlmResponse {
        val blocks = content.map { block ->
            when (block) {
                is AnthropicResponseContent.Text -> ContentBlock.Text(block.text)
                is AnthropicResponseContent.ToolUse -> ContentBlock.ToolUse(
                    id = block.id,
                    name = block.name,
                    input = block.input
                )
            }
        }
        return LlmResponse(
            content = blocks,
            stopReason = parseStopReason(stopReason),
            usage = usage?.let { Usage(it.inputTokens, it.outputTokens) }
        )
    }

    private fun parseStopReason(reason: String?): StopReason = when (reason) {
        "end_turn" -> StopReason.END_TURN
        "tool_use" -> StopReason.TOOL_USE
        "max_tokens" -> StopReason.MAX_TOKENS
        "stop_sequence" -> StopReason.STOP_SEQUENCE
        else -> StopReason.END_TURN
    }
}

/** 流式内容块状态（内部使用） */
private sealed class ContentBlockState {
    data class TextBlock(val text: StringBuilder) : ContentBlockState()
    data class ToolUseBlock(
        val id: String,
        val name: String,
        val inputJson: StringBuilder
    ) : ContentBlockState()
}

class AnthropicApiException(
    val statusCode: Int,
    val errorType: String,
    val errorMessage: String
) : Exception("Anthropic API error ($statusCode/$errorType): $errorMessage")
