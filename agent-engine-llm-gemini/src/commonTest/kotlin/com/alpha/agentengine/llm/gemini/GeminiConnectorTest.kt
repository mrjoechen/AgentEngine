package com.alpha.agentengine.llm.gemini

import com.alpha.agentengine.core.llm.*
import com.alpha.agentengine.core.tool.ToolSchema
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class GeminiConnectorTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    // ===== Helper: 构建带 MockEngine 的 GeminiConnector =====

    private fun createConnector(
        responseBody: String,
        statusCode: HttpStatusCode = HttpStatusCode.OK,
        assertRequest: ((String) -> Unit)? = null
    ): GeminiConnector {
        val mockEngine = MockEngine { request ->
            assertRequest?.invoke(request.body.toByteArray().decodeToString())
            respond(
                content = responseBody,
                status = statusCode,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(this@GeminiConnectorTest.json)
            }
        }
        return GeminiConnector(
            apiKey = "test-key",
            modelId = "gemini-2.5-flash",
            httpClient = client
        )
    }

    private fun createStreamConnector(sseBody: String): GeminiConnector {
        val mockEngine = MockEngine {
            respond(
                content = sseBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val client = HttpClient(mockEngine)
        return GeminiConnector(
            apiKey = "test-key",
            modelId = "gemini-2.5-flash",
            httpClient = client
        )
    }

    private fun simpleRequest(text: String = "Hello") = LlmRequest(
        messages = listOf(Message.user(text)),
        systemPrompt = "You are a helper.",
        maxTokens = 1024
    )

    private fun requestWithTools(text: String = "What's the weather?") = LlmRequest(
        messages = listOf(Message.user(text)),
        systemPrompt = "You are a helper.",
        tools = listOf(
            ToolSchema(
                name = "get_weather",
                description = "Get weather for a location",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("location") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("City name"))
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("location")) }
                }
            )
        ),
        maxTokens = 1024
    )

    // ========================================================================
    // 1. 响应解析测试 — 纯文本
    // ========================================================================

    @Test
    fun testParseTextResponse() = runTest {
        val responseJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [{"text": "Hello! How can I help you?"}],
                "role": "model"
              },
              "finishReason": "STOP",
              "index": 0
            }
          ],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 8,
            "totalTokenCount": 18
          },
          "modelVersion": "gemini-2.5-flash"
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(simpleRequest())

        assertEquals("Hello! How can I help you?", response.text())
        assertFalse(response.hasToolCalls())
        assertEquals(StopReason.END_TURN, response.stopReason)
        assertNotNull(response.usage)
        assertEquals(10, response.usage!!.inputTokens)
        assertEquals(8, response.usage!!.outputTokens)
    }

    // ========================================================================
    // 2. 响应解析测试 — Function Call
    // ========================================================================

    @Test
    fun testParseFunctionCallResponse() = runTest {
        val responseJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "functionCall": {
                      "name": "get_weather",
                      "args": {"location": "San Francisco", "unit": "celsius"}
                    }
                  }
                ],
                "role": "model"
              },
              "finishReason": "STOP",
              "index": 0
            }
          ],
          "usageMetadata": {
            "promptTokenCount": 86,
            "candidatesTokenCount": 20,
            "totalTokenCount": 106
          }
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(requestWithTools())

        assertTrue(response.hasToolCalls())
        assertEquals(StopReason.TOOL_USE, response.stopReason)

        val toolCalls = response.toolCalls()
        assertEquals(1, toolCalls.size)
        assertEquals("get_weather", toolCalls[0].name)
        assertEquals("San Francisco", toolCalls[0].input["location"]?.jsonPrimitive?.content)
        assertEquals("celsius", toolCalls[0].input["unit"]?.jsonPrimitive?.content)
    }

    // ========================================================================
    // 3. 响应解析测试 — 多个 Function Call
    // ========================================================================

    @Test
    fun testParseMultipleFunctionCalls() = runTest {
        val responseJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "functionCall": {
                      "name": "get_weather",
                      "args": {"location": "Tokyo"}
                    }
                  },
                  {
                    "functionCall": {
                      "name": "get_time",
                      "args": {"timezone": "Asia/Tokyo"}
                    }
                  }
                ],
                "role": "model"
              },
              "finishReason": "STOP"
            }
          ],
          "usageMetadata": {
            "promptTokenCount": 50,
            "candidatesTokenCount": 30,
            "totalTokenCount": 80
          }
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(requestWithTools())

        assertTrue(response.hasToolCalls())
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals(2, response.toolCalls().size)
        assertEquals("get_weather", response.toolCalls()[0].name)
        assertEquals("get_time", response.toolCalls()[1].name)
    }

    // ========================================================================
    // 4. 响应解析测试 — 文本 + Function Call 混合
    // ========================================================================

    @Test
    fun testParseTextAndFunctionCallMixed() = runTest {
        val responseJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {"text": "Let me check the weather for you."},
                  {
                    "functionCall": {
                      "name": "get_weather",
                      "args": {"location": "Beijing"}
                    }
                  }
                ],
                "role": "model"
              },
              "finishReason": "STOP"
            }
          ],
          "usageMetadata": {
            "promptTokenCount": 20,
            "candidatesTokenCount": 15,
            "totalTokenCount": 35
          }
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(requestWithTools())

        assertEquals("Let me check the weather for you.", response.text())
        assertTrue(response.hasToolCalls())
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals("get_weather", response.toolCalls()[0].name)
    }

    // ========================================================================
    // 5. 响应解析测试 — usageMetadata camelCase 字段
    // ========================================================================

    @Test
    fun testUsageMetadataCamelCase() = runTest {
        // 确保 camelCase 字段名能正确解析（之前 snake_case 会全部为 0）
        val responseJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [{"text": "Hi"}],
                "role": "model"
              },
              "finishReason": "STOP"
            }
          ],
          "usageMetadata": {
            "promptTokenCount": 123,
            "candidatesTokenCount": 456,
            "totalTokenCount": 579
          }
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(simpleRequest())

        assertNotNull(response.usage)
        assertEquals(123, response.usage!!.inputTokens)
        assertEquals(456, response.usage!!.outputTokens)
    }

    // ========================================================================
    // 6. 响应解析测试 — finishReason 正确映射
    // ========================================================================

    @Test
    fun testFinishReasonMaxTokens() = runTest {
        val responseJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [{"text": "truncated..."}],
                "role": "model"
              },
              "finishReason": "MAX_TOKENS"
            }
          ],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": 100,
            "totalTokenCount": 110
          }
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(simpleRequest())

        assertEquals(StopReason.MAX_TOKENS, response.stopReason)
    }

    // ========================================================================
    // 7. 响应解析测试 — 带 safetyRatings 等额外字段（ignoreUnknownKeys）
    // ========================================================================

    @Test
    fun testParseResponseWithExtraFields() = runTest {
        val responseJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [{"text": "Safe response"}],
                "role": "model"
              },
              "finishReason": "STOP",
              "index": 0,
              "safetyRatings": [
                {"category": "HARM_CATEGORY_SEXUALLY_EXPLICIT", "probability": "NEGLIGIBLE"},
                {"category": "HARM_CATEGORY_HATE_SPEECH", "probability": "NEGLIGIBLE"}
              ]
            }
          ],
          "usageMetadata": {
            "promptTokenCount": 5,
            "candidatesTokenCount": 3,
            "totalTokenCount": 8,
            "cachedContentTokenCount": 0,
            "thoughtsTokenCount": 0
          },
          "modelVersion": "gemini-2.5-flash",
          "responseId": "abc123"
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(simpleRequest())

        assertEquals("Safe response", response.text())
        assertEquals(5, response.usage!!.inputTokens)
    }

    // ========================================================================
    // 8. 响应解析测试 — functionCall 中 args 为 null
    // ========================================================================

    @Test
    fun testFunctionCallWithNullArgs() = runTest {
        val responseJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "functionCall": {
                      "name": "get_current_time"
                    }
                  }
                ],
                "role": "model"
              },
              "finishReason": "STOP"
            }
          ],
          "usageMetadata": {"promptTokenCount": 5, "candidatesTokenCount": 3, "totalTokenCount": 8}
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(requestWithTools())

        assertTrue(response.hasToolCalls())
        assertEquals("get_current_time", response.toolCalls()[0].name)
        assertTrue(response.toolCalls()[0].input.isEmpty())
    }

    // ========================================================================
    // 9. 请求序列化测试 — 验证发送的 JSON 使用 camelCase
    // ========================================================================

    @Test
    fun testRequestSerializationCamelCase() = runTest {
        var capturedBody = ""
        val responseJson = """
        {
          "candidates": [{"content": {"parts": [{"text": "ok"}], "role": "model"}, "finishReason": "STOP"}],
          "usageMetadata": {"promptTokenCount": 1, "candidatesTokenCount": 1, "totalTokenCount": 2}
        }
        """.trimIndent()

        val connector = createConnector(responseJson) { body ->
            capturedBody = body
        }
        connector.createMessage(requestWithTools("test"))

        val requestObj = json.parseToJsonElement(capturedBody).jsonObject

        // 验证顶层字段是 camelCase
        assertTrue(requestObj.containsKey("systemInstruction"), "Should have systemInstruction (camelCase)")
        assertTrue(requestObj.containsKey("generationConfig"), "Should have generationConfig (camelCase)")
        assertFalse(requestObj.containsKey("system_instruction"), "Should NOT have system_instruction (snake_case)")
        assertFalse(requestObj.containsKey("generation_config"), "Should NOT have generation_config (snake_case)")

        // 验证 tools 内部是 camelCase
        val tools = requestObj["tools"]?.jsonArray?.get(0)?.jsonObject
        assertNotNull(tools)
        assertTrue(tools.containsKey("functionDeclarations"), "Should have functionDeclarations (camelCase)")
        assertFalse(tools.containsKey("function_declarations"), "Should NOT have function_declarations (snake_case)")

        // 验证 generationConfig 内部是 camelCase
        val genConfig = requestObj["generationConfig"]?.jsonObject
        assertNotNull(genConfig)
        assertTrue(genConfig.containsKey("maxOutputTokens"), "Should have maxOutputTokens (camelCase)")
        assertFalse(genConfig.containsKey("max_output_tokens"), "Should NOT have max_output_tokens (snake_case)")
    }

    // ========================================================================
    // 10. 请求序列化测试 — Tool Result 转换
    // ========================================================================

    @Test
    fun testToolResultConversion() = runTest {
        var capturedBody = ""
        val responseJson = """
        {
          "candidates": [{"content": {"parts": [{"text": "The weather is sunny."}], "role": "model"}, "finishReason": "STOP"}],
          "usageMetadata": {"promptTokenCount": 1, "candidatesTokenCount": 1, "totalTokenCount": 2}
        }
        """.trimIndent()

        val connector = createConnector(responseJson) { body ->
            capturedBody = body
        }

        // 构造包含 ToolUse 和 ToolResult 的多轮对话
        val messages = listOf(
            Message.user("What's the weather?"),
            Message.assistant(listOf(
                ContentBlock.ToolUse(
                    id = "gemini_call_get_weather",
                    name = "get_weather",
                    input = buildJsonObject { put("location", JsonPrimitive("Tokyo")) }
                )
            )),
            Message.toolResult(listOf(
                ToolCallResult(
                    toolUseId = "gemini_call_get_weather",
                    result = """{"temperature": 22, "condition": "sunny"}"""
                )
            ))
        )

        connector.createMessage(LlmRequest(messages = messages, maxTokens = 1024))

        val requestObj = json.parseToJsonElement(capturedBody).jsonObject
        val contents = requestObj["contents"]!!.jsonArray

        // 第二条消息（model）应包含 functionCall
        val modelContent = contents[1].jsonObject
        assertEquals("model", modelContent["role"]?.jsonPrimitive?.content)
        val modelPart = modelContent["parts"]!!.jsonArray[0].jsonObject
        assertTrue(modelPart.containsKey("functionCall"), "Model part should have functionCall (camelCase)")
        assertFalse(modelPart.containsKey("function_call"), "Should NOT have function_call (snake_case)")

        // 第三条消息（user tool result）应包含 functionResponse
        val toolResultContent = contents[2].jsonObject
        assertEquals("user", toolResultContent["role"]?.jsonPrimitive?.content)
        val toolResultPart = toolResultContent["parts"]!!.jsonArray[0].jsonObject
        assertTrue(toolResultPart.containsKey("functionResponse"), "Tool result should have functionResponse (camelCase)")
        assertFalse(toolResultPart.containsKey("function_response"), "Should NOT have function_response (snake_case)")

        // functionResponse.name 应该是函数名（get_weather）而不是 call ID
        val funcResponse = toolResultPart["functionResponse"]!!.jsonObject
        assertEquals("get_weather", funcResponse["name"]?.jsonPrimitive?.content,
            "functionResponse.name should be the function name, not the call ID")
    }

    // ========================================================================
    // 11. 请求序列化测试 — Schema 清理 (additionalProperties 移除)
    // ========================================================================

    @Test
    fun testSchemaCleanup() = runTest {
        var capturedBody = ""
        val responseJson = """
        {
          "candidates": [{"content": {"parts": [{"text": "ok"}], "role": "model"}, "finishReason": "STOP"}],
          "usageMetadata": {"promptTokenCount": 1, "candidatesTokenCount": 1, "totalTokenCount": 2}
        }
        """.trimIndent()

        val toolWithAdditionalProps = ToolSchema(
            name = "test_tool",
            description = "A test tool",
            inputSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("additionalProperties", JsonPrimitive(false))
                putJsonObject("properties") {
                    putJsonObject("name") {
                        put("type", JsonPrimitive("string"))
                        put("additionalProperties", JsonPrimitive(false))
                    }
                }
            }
        )

        val connector = createConnector(responseJson) { body -> capturedBody = body }
        connector.createMessage(LlmRequest(
            messages = listOf(Message.user("test")),
            tools = listOf(toolWithAdditionalProps),
            maxTokens = 512
        ))

        val requestObj = json.parseToJsonElement(capturedBody).jsonObject
        val funcDecl = requestObj["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
        val params = funcDecl["parameters"]!!.jsonObject

        assertFalse(params.containsKey("additionalProperties"),
            "additionalProperties should be removed from top level")
        assertFalse(params["properties"]!!.jsonObject["name"]!!.jsonObject.containsKey("additionalProperties"),
            "additionalProperties should be removed from nested objects")
    }

    // ========================================================================
    // 12. 错误处理测试 — HTTP 错误
    // ========================================================================

    @Test
    fun testHttpError() = runTest {
        val errorJson = """
        {
          "error": {
            "code": 400,
            "message": "API key not valid. Please pass a valid API key.",
            "status": "INVALID_ARGUMENT"
          }
        }
        """.trimIndent()

        val connector = createConnector(errorJson, HttpStatusCode.BadRequest)

        val exception = assertFailsWith<GeminiApiException> {
            connector.createMessage(simpleRequest())
        }

        assertEquals(400, exception.statusCode)
        assertTrue(exception.errorMessage.contains("API key not valid"))
        assertEquals("INVALID_ARGUMENT", exception.errorStatus)
    }

    // ========================================================================
    // 13. 错误处理测试 — 空 candidates
    // ========================================================================

    @Test
    fun testEmptyCandidates() = runTest {
        val responseJson = """
        {
          "candidates": [],
          "usageMetadata": {"promptTokenCount": 5, "candidatesTokenCount": 0, "totalTokenCount": 5}
        }
        """.trimIndent()

        val connector = createConnector(responseJson)

        assertFailsWith<GeminiApiException> {
            connector.createMessage(simpleRequest())
        }
    }

    // ========================================================================
    // 14. 流式测试 — 纯文本 SSE
    // ========================================================================

    @Test
    fun testStreamTextResponse() = runTest {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"}}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":1,"totalTokenCount":6}}""")
            appendLine()
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":" World"}],"role":"model"}}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3,"totalTokenCount":8}}""")
            appendLine()
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"!"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":4,"totalTokenCount":9}}""")
            appendLine()
        }

        val connector = createStreamConnector(sseBody)
        val events = connector.createMessageStream(simpleRequest()).toList()

        // 应有 3 个 TextDelta + 1 个 MessageComplete
        val textDeltas = events.filterIsInstance<LlmStreamEvent.TextDelta>()
        assertEquals(3, textDeltas.size)
        assertEquals("Hello", textDeltas[0].text)
        assertEquals(" World", textDeltas[1].text)
        assertEquals("!", textDeltas[2].text)

        val complete = events.filterIsInstance<LlmStreamEvent.MessageComplete>()
        assertEquals(1, complete.size)
        assertEquals("Hello World!", complete[0].response.text())
        assertEquals(StopReason.END_TURN, complete[0].response.stopReason)
        assertEquals(5, complete[0].response.usage?.inputTokens)
        assertEquals(4, complete[0].response.usage?.outputTokens)
    }

    // ========================================================================
    // 15. 流式测试 — Function Call SSE
    // ========================================================================

    @Test
    fun testStreamFunctionCallResponse() = runTest {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"location":"Tokyo"}}}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":10,"totalTokenCount":30}}""")
            appendLine()
        }

        val connector = createStreamConnector(sseBody)
        val events = connector.createMessageStream(requestWithTools()).toList()

        val toolStarts = events.filterIsInstance<LlmStreamEvent.ToolUseStart>()
        assertEquals(1, toolStarts.size)
        assertEquals("get_weather", toolStarts[0].name)

        val inputDeltas = events.filterIsInstance<LlmStreamEvent.ToolUseInputDelta>()
        assertEquals(1, inputDeltas.size)
        val inputJson = json.parseToJsonElement(inputDeltas[0].delta).jsonObject
        assertEquals("Tokyo", inputJson["location"]?.jsonPrimitive?.content)

        val complete = events.filterIsInstance<LlmStreamEvent.MessageComplete>().first()
        assertEquals(StopReason.TOOL_USE, complete.response.stopReason)
        assertTrue(complete.response.hasToolCalls())
        assertEquals("get_weather", complete.response.toolCalls()[0].name)
    }

    // ========================================================================
    // 16. 模型序列化 — 直接验证 GeminiResponse 反序列化
    // ========================================================================

    @Test
    fun testGeminiResponseDeserialization() {
        // 模拟真实 Gemini API 返回的完整 JSON
        val realApiResponse = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {"text": "The answer is 42."}
                ],
                "role": "model"
              },
              "finishReason": "STOP",
              "index": 0,
              "safetyRatings": [
                {"category": "HARM_CATEGORY_SEXUALLY_EXPLICIT", "probability": "NEGLIGIBLE"},
                {"category": "HARM_CATEGORY_HATE_SPEECH", "probability": "NEGLIGIBLE"},
                {"category": "HARM_CATEGORY_HARASSMENT", "probability": "NEGLIGIBLE"},
                {"category": "HARM_CATEGORY_DANGEROUS_CONTENT", "probability": "NEGLIGIBLE"}
              ]
            }
          ],
          "usageMetadata": {
            "promptTokenCount": 12,
            "candidatesTokenCount": 7,
            "totalTokenCount": 19
          },
          "modelVersion": "gemini-2.5-flash-preview-05-20"
        }
        """.trimIndent()

        val response = json.decodeFromString(GeminiResponse.serializer(), realApiResponse)

        assertNotNull(response.candidates)
        assertEquals(1, response.candidates!!.size)

        val candidate = response.candidates!![0]
        assertEquals("STOP", candidate.finishReason)
        assertEquals(0, candidate.index)
        assertNotNull(candidate.safetyRatings)

        val parts = candidate.content!!.parts
        assertEquals(1, parts.size)
        assertEquals("The answer is 42.", parts[0].text)

        assertNotNull(response.usageMetadata)
        assertEquals(12, response.usageMetadata!!.promptTokenCount)
        assertEquals(7, response.usageMetadata!!.candidatesTokenCount)
        assertEquals(19, response.usageMetadata!!.totalTokenCount)
    }

    // ========================================================================
    // 17. 模型序列化 — snake_case 字段反序列化失败验证
    // ========================================================================

    @Test
    fun testSnakeCaseFieldsAreNotParsed() {
        // 这个 JSON 使用 snake_case，模拟之前的 bug：如果模型用 @SerialName("snake_case")
        // 那么真实 API 的 camelCase 会被忽略（全变成默认值 0）
        val snakeCaseJson = """
        {
          "candidates": [
            {
              "content": {"parts": [{"text": "test"}], "role": "model"},
              "finish_reason": "STOP"
            }
          ],
          "usage_metadata": {
            "prompt_token_count": 100,
            "candidates_token_count": 50,
            "total_token_count": 150
          }
        }
        """.trimIndent()

        val response = json.decodeFromString(GeminiResponse.serializer(), snakeCaseJson)

        // snake_case 的 usage_metadata 不会被识别 → null
        assertNull(response.usageMetadata, "snake_case usage_metadata should NOT be parsed")

        // snake_case 的 finish_reason 不会被识别 → null
        assertNull(response.candidates!![0].finishReason, "snake_case finish_reason should NOT be parsed")
    }

    // ========================================================================
    // 18. 模型序列化 — functionCall camelCase 反序列化
    // ========================================================================

    @Test
    fun testFunctionCallCamelCaseDeserialization() {
        val fcJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "functionCall": {
                      "name": "schedule_meeting",
                      "args": {
                        "attendees": ["Bob", "Alice"],
                        "date": "2025-03-27",
                        "time": "10:00"
                      }
                    }
                  }
                ],
                "role": "model"
              },
              "finishReason": "STOP"
            }
          ],
          "usageMetadata": {"promptTokenCount": 86, "candidatesTokenCount": 20, "totalTokenCount": 106}
        }
        """.trimIndent()

        val response = json.decodeFromString(GeminiResponse.serializer(), fcJson)
        val fc = response.candidates!![0].content!!.parts[0].functionCall

        assertNotNull(fc, "functionCall (camelCase) should be parsed correctly")
        assertEquals("schedule_meeting", fc.name)
        assertNotNull(fc.args)
        assertTrue(fc.args!!.containsKey("attendees"))
        assertTrue(fc.args!!.containsKey("date"))
    }

    // ========================================================================
    // 19. 模型序列化 — 请求序列化输出验证
    // ========================================================================

    @Test
    fun testGeminiRequestSerialization() {
        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    role = "user",
                    parts = listOf(GeminiPart(text = "Hello"))
                )
            ),
            tools = listOf(
                GeminiTool(
                    functionDeclarations = listOf(
                        GeminiFunctionDeclaration(
                            name = "test_func",
                            description = "A test function",
                            parameters = buildJsonObject {
                                put("type", JsonPrimitive("object"))
                            }
                        )
                    )
                )
            ),
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = "Be helpful"))),
            generationConfig = GeminiGenerationConfig(
                temperature = 0.7,
                maxOutputTokens = 1024
            )
        )

        val jsonStr = json.encodeToString(GeminiRequest.serializer(), request)
        val obj = json.parseToJsonElement(jsonStr).jsonObject

        // 全部 camelCase
        assertTrue(obj.containsKey("contents"))
        assertTrue(obj.containsKey("tools"))
        assertTrue(obj.containsKey("systemInstruction"))
        assertTrue(obj.containsKey("generationConfig"))

        assertFalse(obj.containsKey("system_instruction"))
        assertFalse(obj.containsKey("generation_config"))

        val tools = obj["tools"]!!.jsonArray[0].jsonObject
        assertTrue(tools.containsKey("functionDeclarations"))
        assertFalse(tools.containsKey("function_declarations"))

        val genConfig = obj["generationConfig"]!!.jsonObject
        assertTrue(genConfig.containsKey("maxOutputTokens"))
        assertFalse(genConfig.containsKey("max_output_tokens"))
        assertEquals(0.7, genConfig["temperature"]!!.jsonPrimitive.double)
    }

    // ========================================================================
    // 20. 请求 URL 和 API Key 测试
    // ========================================================================

    @Test
    fun testRequestUrlContainsApiKey() = runTest {
        var capturedUrl = ""
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = """{"candidates":[{"content":{"parts":[{"text":"ok"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,"totalTokenCount":2}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(this@GeminiConnectorTest.json)
            }
        }
        val connector = GeminiConnector(
            apiKey = "MY_TEST_KEY",
            modelId = "gemini-2.5-flash",
            httpClient = client
        )

        connector.createMessage(simpleRequest())

        assertTrue(capturedUrl.contains("key=MY_TEST_KEY"), "URL should contain API key")
        assertTrue(capturedUrl.contains("models/gemini-2.5-flash:generateContent"), "URL should contain model and action")
    }

    // ========================================================================
    // 21. 流式测试 — 中文 UTF-8 多字节字符（乱码修复验证）
    // ========================================================================

    @Test
    fun testStreamChineseUtf8() = runTest {
        val sseBody = buildString {
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"你好"}],"role":"model"}}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2,"totalTokenCount":7}}""")
            appendLine()
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"，世界！"}],"role":"model"}}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":5,"totalTokenCount":10}}""")
            appendLine()
            appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"🎉"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":6,"totalTokenCount":11}}""")
            appendLine()
        }

        val connector = createStreamConnector(sseBody)
        val events = connector.createMessageStream(simpleRequest("你好")).toList()

        val textDeltas = events.filterIsInstance<LlmStreamEvent.TextDelta>()
        assertEquals(3, textDeltas.size)
        assertEquals("你好", textDeltas[0].text)
        assertEquals("，世界！", textDeltas[1].text)
        assertEquals("🎉", textDeltas[2].text)

        val complete = events.filterIsInstance<LlmStreamEvent.MessageComplete>().first()
        assertEquals("你好，世界！🎉", complete.response.text())
    }

    // ========================================================================
    // 22. 非流式测试 — 中文 UTF-8 响应
    // ========================================================================

    @Test
    fun testChineseTextResponse() = runTest {
        val responseJson = """
        {
          "candidates": [
            {
              "content": {
                "parts": [{"text": "你好！我是 Gemini，很高兴为你服务。🤖"}],
                "role": "model"
              },
              "finishReason": "STOP"
            }
          ],
          "usageMetadata": {"promptTokenCount": 5, "candidatesTokenCount": 15, "totalTokenCount": 20}
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(simpleRequest("你好"))

        assertEquals("你好！我是 Gemini，很高兴为你服务。🤖", response.text())
    }

    // ========================================================================
    // 23. 日志输出测试 — enableLogging 参数
    // ========================================================================

    @Test
    fun testLoggingEnabled() = runTest {
        val responseJson = """
        {
          "candidates": [{"content": {"parts": [{"text": "ok"}], "role": "model"}, "finishReason": "STOP"}],
          "usageMetadata": {"promptTokenCount": 1, "candidatesTokenCount": 1, "totalTokenCount": 2}
        }
        """.trimIndent()

        val mockEngine = MockEngine {
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(this@GeminiConnectorTest.json)
            }
        }

        // enableLogging = true 不应抛异常
        val connector = GeminiConnector(
            apiKey = "test-key",
            modelId = "gemini-2.5-flash",
            enableLogging = true,
            httpClient = client
        )

        val response = connector.createMessage(simpleRequest())
        assertEquals("ok", response.text())
    }

    // ========================================================================
    // 24. 流式测试 — \r\n 行尾处理
    // ========================================================================

    @Test
    fun testStreamCrLfLineEndings() = runTest {
        // Gemini SSE 可能使用 \r\n 作为行尾
        val sseBody = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}],\"role\":\"model\"},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":1,\"totalTokenCount\":6}}\r\n\r\n"

        val connector = createStreamConnector(sseBody)
        val events = connector.createMessageStream(simpleRequest()).toList()

        val textDeltas = events.filterIsInstance<LlmStreamEvent.TextDelta>()
        assertEquals(1, textDeltas.size)
        assertEquals("Hello", textDeltas[0].text)
    }
}
