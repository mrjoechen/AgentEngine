package com.alpha.agentengine.llm.gemini

import com.alpha.agentengine.core.llm.*
import com.alpha.agentengine.core.tool.ToolSchema
import io.ktor.client.*
import io.ktor.client.call.*
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
 * Google Gemini API 连接器。
 *
 * 用法:
 * ```
 * val connector = GeminiConnector(apiKey = "AIza...")
 * ```
 *
 * 支持 Gemini REST API v1beta (generateContent / streamGenerateContent)，
 * 包含 Function Calling（Tool Use）和流式输出。
 *
 * @param enableLogging 启用请求/响应日志输出（调试用）
 */
class GeminiConnector(
    private val apiKey: String,
    override val modelId: String = "gemini-2.5-flash",
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val apiVersion: String = "v1beta",
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
            json(this@GeminiConnector.json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
        }
    }

    private val messagesUrl get() = "$baseUrl/$apiVersion/models/$modelId"

    private fun log(tag: String, message: String) {
        if (enableLogging) {
            println("[GeminiConnector/$tag] $message")
        }
    }

    override suspend fun createMessage(request: LlmRequest): LlmResponse {
        val geminiRequest = request.toGeminiRequest()

        val requestJson = json.encodeToString(GeminiRequest.serializer(), geminiRequest)
        log("REQUEST", "POST $messagesUrl:generateContent")
        log("REQUEST", requestJson)

        val httpResponse = client.post("$messagesUrl:generateContent") {
            parameter("key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(geminiRequest)
        }

        log("RESPONSE", "HTTP ${httpResponse.status.value}")

        if (!httpResponse.status.isSuccess()) {
            val errorText = httpResponse.bodyAsText()
            log("ERROR", errorText)
            val errorBody = try {
                json.decodeFromString(GeminiErrorResponse.serializer(), errorText)
            } catch (_: Exception) {
                null
            }
            throw GeminiApiException(
                statusCode = httpResponse.status.value,
                errorMessage = errorBody?.error?.message ?: "HTTP ${httpResponse.status.value}",
                errorStatus = errorBody?.error?.status ?: "UNKNOWN"
            )
        }

        val responseText = httpResponse.bodyAsText()
        log("RESPONSE", responseText)

        val response = json.decodeFromString(GeminiResponse.serializer(), responseText)
        return response.toLlmResponse()
    }

    override fun createMessageStream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val geminiRequest = request.toGeminiRequest()
        val requestBody = json.encodeToString(GeminiRequest.serializer(), geminiRequest)

        log("STREAM_REQUEST", "POST $messagesUrl:streamGenerateContent?alt=sse")
        log("STREAM_REQUEST", requestBody)

        val accumulatedText = StringBuilder()
        val accumulatedToolCalls = mutableListOf<ContentBlock.ToolUse>()
        var inputTokens = 0
        var outputTokens = 0
        var stopReason = StopReason.END_TURN

        client.preparePost("$messagesUrl:streamGenerateContent") {
            parameter("key", apiKey)
            parameter("alt", "sse")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }.execute { response ->
            log("STREAM_RESPONSE", "HTTP ${response.status.value}")

            val channel: ByteReadChannel = response.bodyAsChannel()

            // 使用 ByteArray 缓冲区正确处理 UTF-8 多字节字符
            val byteBuffer = ByteArray(8192)
            val lineBuffer = StringBuilder()

            while (!channel.isClosedForRead) {
                val bytesRead = try {
                    channel.readAvailable(byteBuffer)
                } catch (_: Exception) {
                    break
                }
                if (bytesRead <= 0) continue

                // 正确解码 UTF-8
                val chunk = byteBuffer.decodeToString(0, bytesRead)

                for (char in chunk) {
                    if (char == '\n') {
                        val line = lineBuffer.toString()
                        lineBuffer.clear()

                        if (line.startsWith("data: ")) {
                            val data = line.removePrefix("data: ").trim()
                            if (data.isEmpty()) continue

                            log("STREAM_DATA", data)

                            val geminiResponse = try {
                                json.decodeFromString(GeminiResponse.serializer(), data)
                            } catch (e: Exception) {
                                log("STREAM_PARSE_ERROR", "Failed to parse: ${e.message}")
                                continue
                            }

                            geminiResponse.error?.let { error ->
                                log("STREAM_ERROR", "API error: ${error.message}")
                                emit(LlmStreamEvent.Error("Gemini error: ${error.message}"))
                                return@execute
                            }

                            geminiResponse.usageMetadata?.let { usage ->
                                inputTokens = usage.promptTokenCount
                                outputTokens = usage.candidatesTokenCount
                            }

                            val candidate = geminiResponse.candidates?.firstOrNull() ?: continue

                            candidate.finishReason?.let { reason ->
                                stopReason = parseFinishReason(reason)
                            }

                            val parts = candidate.content?.parts ?: continue
                            for (part in parts) {
                                when {
                                    part.text != null -> {
                                        accumulatedText.append(part.text)
                                        emit(LlmStreamEvent.TextDelta(part.text))
                                    }
                                    part.functionCall != null -> {
                                        val fc = part.functionCall
                                        val callId = "gemini_call_${fc.name}_${accumulatedToolCalls.size}"
                                        val input = fc.args ?: buildJsonObject {}
                                        accumulatedToolCalls.add(ContentBlock.ToolUse(callId, fc.name, input))
                                        emit(LlmStreamEvent.ToolUseStart(id = callId, name = fc.name))
                                        emit(LlmStreamEvent.ToolUseInputDelta(
                                            json.encodeToString(JsonObject.serializer(), input)
                                        ))
                                    }
                                }
                            }
                        }
                    } else if (char != '\r') {
                        lineBuffer.append(char)
                    }
                }
            }
        }

        val finalBlocks = mutableListOf<ContentBlock>()
        if (accumulatedText.isNotEmpty()) {
            finalBlocks.add(ContentBlock.Text(accumulatedText.toString()))
        }
        finalBlocks.addAll(accumulatedToolCalls)

        if (accumulatedToolCalls.isNotEmpty()) {
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

    private fun LlmRequest.toGeminiRequest(): GeminiRequest {
        val callIdToName = mutableMapOf<String, String>()
        for (msg in messages) {
            for (block in msg.content) {
                if (block is ContentBlock.ToolUse) {
                    callIdToName[block.id] = block.name
                }
            }
        }

        val geminiContents = messages.map { it.toGeminiContent(callIdToName) }

        val systemContent = systemPrompt?.let {
            GeminiContent(parts = listOf(GeminiPart(text = it)))
        }

        val geminiTools = if (tools.isNotEmpty()) {
            listOf(GeminiTool(
                functionDeclarations = tools.map { it.toGeminiFunctionDeclaration() }
            ))
        } else null

        return GeminiRequest(
            contents = geminiContents,
            tools = geminiTools,
            systemInstruction = systemContent,
            generationConfig = GeminiGenerationConfig(
                temperature = temperature,
                maxOutputTokens = maxTokens
            )
        )
    }

    private fun Message.toGeminiContent(callIdToName: Map<String, String>): GeminiContent {
        val geminiRole = when (role) {
            Role.USER -> "user"
            Role.ASSISTANT -> "model"
        }

        val parts = content.map { block ->
            when (block) {
                is ContentBlock.Text -> GeminiPart(text = block.text)
                is ContentBlock.ToolUse -> GeminiPart(
                    functionCall = GeminiFunctionCall(
                        name = block.name,
                        args = block.input
                    )
                )
                is ContentBlock.ToolResultBlock -> GeminiPart(
                    functionResponse = GeminiFunctionResponse(
                        name = callIdToName[block.toolUseId] ?: block.toolUseId,
                        response = buildJsonObject {
                            put("result", JsonPrimitive(block.result))
                            if (block.isError) put("error", JsonPrimitive(true))
                        }
                    )
                )
            }
        }

        return GeminiContent(role = geminiRole, parts = parts)
    }

    private fun ToolSchema.toGeminiFunctionDeclaration(): GeminiFunctionDeclaration {
        val cleanedSchema = cleanSchemaForGemini(inputSchema)
        return GeminiFunctionDeclaration(
            name = name,
            description = description,
            parameters = cleanedSchema.takeIf { it.isNotEmpty() }
        )
    }

    private fun cleanSchemaForGemini(schema: JsonObject): JsonObject {
        if (schema.isEmpty()) return schema
        return buildJsonObject {
            for ((key, value) in schema) {
                if (key == "additionalProperties") continue
                if (value is JsonObject) {
                    put(key, cleanSchemaForGemini(value))
                } else {
                    put(key, value)
                }
            }
        }
    }

    private fun GeminiResponse.toLlmResponse(): LlmResponse {
        val candidate = candidates?.firstOrNull()
            ?: throw GeminiApiException(0, error?.message ?: "No candidates in response", "NO_CANDIDATES")

        val blocks = candidate.content?.parts?.mapNotNull { part ->
            when {
                part.text != null -> ContentBlock.Text(part.text)
                part.functionCall != null -> {
                    val fc = part.functionCall
                    ContentBlock.ToolUse(
                        id = "gemini_call_${fc.name}",
                        name = fc.name,
                        input = fc.args ?: buildJsonObject {}
                    )
                }
                else -> null
            }
        } ?: emptyList()

        val hasToolCalls = blocks.any { it is ContentBlock.ToolUse }
        val stopReason = if (hasToolCalls) StopReason.TOOL_USE else parseFinishReason(candidate.finishReason)

        return LlmResponse(
            content = blocks,
            stopReason = stopReason,
            usage = usageMetadata?.let {
                Usage(it.promptTokenCount, it.candidatesTokenCount)
            }
        )
    }

    private fun parseFinishReason(reason: String?): StopReason = when (reason) {
        "STOP" -> StopReason.END_TURN
        "MAX_TOKENS" -> StopReason.MAX_TOKENS
        "STOP_SEQUENCE" -> StopReason.STOP_SEQUENCE
        else -> StopReason.END_TURN
    }
}

class GeminiApiException(
    val statusCode: Int,
    val errorMessage: String,
    val errorStatus: String
) : Exception("Gemini API error ($statusCode/$errorStatus): $errorMessage")
