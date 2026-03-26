package com.alpha.agentengine.llm.openai

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

class OpenAiConnectorTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private fun createConnector(
        responseBody: String,
        statusCode: HttpStatusCode = HttpStatusCode.OK,
        assertRequest: ((String) -> Unit)? = null
    ): OpenAiConnector {
        val mockEngine = MockEngine { request ->
            assertRequest?.invoke(request.body.toByteArray().decodeToString())
            respond(
                content = responseBody,
                status = statusCode,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(this@OpenAiConnectorTest.json) }
        }
        return OpenAiConnector(apiKey = "test-key", modelId = "gpt-4o", httpClient = client)
    }

    private fun createStreamConnector(sseBody: String): OpenAiConnector {
        val mockEngine = MockEngine {
            respond(
                content = sseBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        return OpenAiConnector(apiKey = "test-key", modelId = "gpt-4o", httpClient = HttpClient(mockEngine))
    }

    private fun simpleRequest(text: String = "Hello") = LlmRequest(
        messages = listOf(Message.user(text)),
        systemPrompt = "You are a helper.",
        maxTokens = 1024
    )

    private fun requestWithTools() = LlmRequest(
        messages = listOf(Message.user("What's the weather?")),
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
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("location")) }
                }
            )
        ),
        maxTokens = 1024
    )

    // ========================================================================
    // 1. 纯文本响应
    // ========================================================================

    @Test
    fun testParseTextResponse() = runTest {
        val responseJson = """
        {
          "id": "chatcmpl-abc123",
          "model": "gpt-4o",
          "choices": [{
            "index": 0,
            "message": {"role": "assistant", "content": "Hello! How can I help?"},
            "finish_reason": "stop"
          }],
          "usage": {"prompt_tokens": 10, "completion_tokens": 6, "total_tokens": 16}
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(simpleRequest())

        assertEquals("Hello! How can I help?", response.text())
        assertFalse(response.hasToolCalls())
        assertEquals(StopReason.END_TURN, response.stopReason)
        assertEquals(10, response.usage!!.inputTokens)
        assertEquals(6, response.usage!!.outputTokens)
    }

    // ========================================================================
    // 2. 单个 Tool Call
    // ========================================================================

    @Test
    fun testParseFunctionCallResponse() = runTest {
        val responseJson = """
        {
          "id": "chatcmpl-abc123",
          "model": "gpt-4o",
          "choices": [{
            "index": 0,
            "message": {
              "role": "assistant",
              "content": null,
              "tool_calls": [{
                "id": "call_abc123",
                "type": "function",
                "function": {
                  "name": "get_weather",
                  "arguments": "{\"location\": \"San Francisco\"}"
                }
              }]
            },
            "finish_reason": "tool_calls"
          }],
          "usage": {"prompt_tokens": 50, "completion_tokens": 20, "total_tokens": 70}
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(requestWithTools())

        assertTrue(response.hasToolCalls())
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals(1, response.toolCalls().size)
        assertEquals("get_weather", response.toolCalls()[0].name)
        assertEquals("call_abc123", response.toolCalls()[0].id)
        assertEquals("San Francisco", response.toolCalls()[0].input["location"]?.jsonPrimitive?.content)
    }

    // ========================================================================
    // 3. 多个 Tool Call
    // ========================================================================

    @Test
    fun testParseMultipleToolCalls() = runTest {
        val responseJson = """
        {
          "choices": [{
            "message": {
              "role": "assistant",
              "tool_calls": [
                {"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{\"location\":\"Tokyo\"}"}},
                {"id": "call_2", "type": "function", "function": {"name": "get_time", "arguments": "{\"tz\":\"Asia/Tokyo\"}"}}
              ]
            },
            "finish_reason": "tool_calls"
          }],
          "usage": {"prompt_tokens": 50, "completion_tokens": 30, "total_tokens": 80}
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(requestWithTools())

        assertEquals(2, response.toolCalls().size)
        assertEquals("get_weather", response.toolCalls()[0].name)
        assertEquals("get_time", response.toolCalls()[1].name)
    }

    // ========================================================================
    // 4. 文本 + Tool Call 混合
    // ========================================================================

    @Test
    fun testTextAndToolCallMixed() = runTest {
        val responseJson = """
        {
          "choices": [{
            "message": {
              "role": "assistant",
              "content": "Let me check.",
              "tool_calls": [{"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{\"location\":\"Beijing\"}"}}]
            },
            "finish_reason": "tool_calls"
          }],
          "usage": {"prompt_tokens": 20, "completion_tokens": 15, "total_tokens": 35}
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(requestWithTools())

        assertEquals("Let me check.", response.text())
        assertTrue(response.hasToolCalls())
        assertEquals(StopReason.TOOL_USE, response.stopReason)
    }

    // ========================================================================
    // 5. finish_reason: length (MAX_TOKENS)
    // ========================================================================

    @Test
    fun testFinishReasonLength() = runTest {
        val responseJson = """
        {
          "choices": [{
            "message": {"role": "assistant", "content": "truncated..."},
            "finish_reason": "length"
          }],
          "usage": {"prompt_tokens": 10, "completion_tokens": 100, "total_tokens": 110}
        }
        """.trimIndent()

        val connector = createConnector(responseJson)
        val response = connector.createMessage(simpleRequest())
        assertEquals(StopReason.MAX_TOKENS, response.stopReason)
    }

    // ========================================================================
    // 6. 请求序列化 — snake_case 验证
    // ========================================================================

    @Test
    fun testRequestSerializationSnakeCase() = runTest {
        var capturedBody = ""
        val responseJson = """
        {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimIndent()

        val connector = createConnector(responseJson) { body -> capturedBody = body }
        connector.createMessage(requestWithTools())

        val obj = json.parseToJsonElement(capturedBody).jsonObject

        // 验证 snake_case 字段
        assertTrue(obj.containsKey("max_tokens"), "Should have max_tokens (snake_case)")
        assertFalse(obj.containsKey("maxTokens"), "Should NOT have maxTokens (camelCase)")

        // tools 结构
        val tool = obj["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", tool["type"]?.jsonPrimitive?.content)
        assertTrue(tool.containsKey("function"))

        val func = tool["function"]!!.jsonObject
        assertEquals("get_weather", func["name"]?.jsonPrimitive?.content)
        assertTrue(func.containsKey("parameters"))

        // messages 第一条应该是 system
        val msgs = obj["messages"]!!.jsonArray
        assertEquals("system", msgs[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("user", msgs[1].jsonObject["role"]?.jsonPrimitive?.content)
    }

    // ========================================================================
    // 7. Tool Result 转换 — tool role 消息
    // ========================================================================

    @Test
    fun testToolResultConversion() = runTest {
        var capturedBody = ""
        val responseJson = """
        {"choices":[{"message":{"role":"assistant","content":"It's sunny!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """.trimIndent()

        val messages = listOf(
            Message.user("What's the weather?"),
            Message.assistant(listOf(
                ContentBlock.ToolUse(
                    id = "call_abc",
                    name = "get_weather",
                    input = buildJsonObject { put("location", JsonPrimitive("Tokyo")) }
                )
            )),
            Message.toolResult(listOf(
                ToolCallResult(toolUseId = "call_abc", result = """{"temp": 22}""")
            ))
        )

        val connector = createConnector(responseJson) { body -> capturedBody = body }
        connector.createMessage(LlmRequest(messages = messages, maxTokens = 1024))

        val obj = json.parseToJsonElement(capturedBody).jsonObject
        val msgs = obj["messages"]!!.jsonArray

        // assistant 消息应包含 tool_calls
        val assistantMsg = msgs[1].jsonObject
        assertEquals("assistant", assistantMsg["role"]?.jsonPrimitive?.content)
        assertTrue(assistantMsg.containsKey("tool_calls"))
        val tc = assistantMsg["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("call_abc", tc["id"]?.jsonPrimitive?.content)

        // tool result 应为 role=tool 的独立消息
        val toolMsg = msgs[2].jsonObject
        assertEquals("tool", toolMsg["role"]?.jsonPrimitive?.content)
        assertEquals("call_abc", toolMsg["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("""{"temp": 22}""", toolMsg["content"]?.jsonPrimitive?.content)
    }

    // ========================================================================
    // 8. HTTP 错误
    // ========================================================================

    @Test
    fun testHttpError() = runTest {
        val errorJson = """
        {"error": {"message": "Incorrect API key provided", "type": "invalid_request_error", "code": "invalid_api_key"}}
        """.trimIndent()

        val connector = createConnector(errorJson, HttpStatusCode.Unauthorized)
        val exception = assertFailsWith<OpenAiApiException> {
            connector.createMessage(simpleRequest())
        }
        assertEquals(401, exception.statusCode)
        assertTrue(exception.errorMessage.contains("API key"))
    }

    // ========================================================================
    // 9. 空 choices
    // ========================================================================

    @Test
    fun testEmptyChoices() = runTest {
        val responseJson = """{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":0,"total_tokens":5}}"""
        val connector = createConnector(responseJson)
        assertFailsWith<OpenAiApiException> {
            connector.createMessage(simpleRequest())
        }
    }

    // ========================================================================
    // 10. 流式 — 纯文本
    // ========================================================================

    @Test
    fun testStreamTextResponse() = runTest {
        val sseBody = buildString {
            appendLine("""data: {"choices":[{"delta":{"role":"assistant","content":"Hello"},"index":0}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"delta":{"content":" World"},"index":0}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"delta":{"content":"!"},"index":0,"finish_reason":"stop"}]}""")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }

        val connector = createStreamConnector(sseBody)
        val events = connector.createMessageStream(simpleRequest()).toList()

        val textDeltas = events.filterIsInstance<LlmStreamEvent.TextDelta>()
        assertEquals(3, textDeltas.size)
        assertEquals("Hello", textDeltas[0].text)
        assertEquals(" World", textDeltas[1].text)
        assertEquals("!", textDeltas[2].text)

        val complete = events.filterIsInstance<LlmStreamEvent.MessageComplete>().first()
        assertEquals("Hello World!", complete.response.text())
        assertEquals(StopReason.END_TURN, complete.response.stopReason)
    }

    // ========================================================================
    // 11. 流式 — Tool Call（增量 arguments）
    // ========================================================================

    @Test
    fun testStreamToolCallResponse() = runTest {
        val sseBody = buildString {
            // 第一个 chunk：tool_call 开始，带 id 和 name
            appendLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_xyz","type":"function","function":{"name":"get_weather","arguments":""}}]},"index":0}]}""")
            appendLine()
            // 第二个 chunk：arguments 增量
            appendLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"location\":"}}]},"index":0}]}""")
            appendLine()
            // 第三个 chunk：arguments 继续
            appendLine("""data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Tokyo\"}"}}]},"index":0}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"delta":{},"index":0,"finish_reason":"tool_calls"}]}""")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }

        val connector = createStreamConnector(sseBody)
        val events = connector.createMessageStream(requestWithTools()).toList()

        val toolStarts = events.filterIsInstance<LlmStreamEvent.ToolUseStart>()
        assertEquals(1, toolStarts.size)
        assertEquals("get_weather", toolStarts[0].name)
        assertEquals("call_xyz", toolStarts[0].id)

        val inputDeltas = events.filterIsInstance<LlmStreamEvent.ToolUseInputDelta>()
        assertTrue(inputDeltas.size >= 2)

        val complete = events.filterIsInstance<LlmStreamEvent.MessageComplete>().first()
        assertEquals(StopReason.TOOL_USE, complete.response.stopReason)
        assertTrue(complete.response.hasToolCalls())
        assertEquals("Tokyo", complete.response.toolCalls()[0].input["location"]?.jsonPrimitive?.content)
    }

    // ========================================================================
    // 12. 流式 — 中文 UTF-8
    // ========================================================================

    @Test
    fun testStreamChineseUtf8() = runTest {
        val sseBody = buildString {
            appendLine("""data: {"choices":[{"delta":{"role":"assistant","content":"你好"},"index":0}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"delta":{"content":"，世界！🎉"},"index":0,"finish_reason":"stop"}]}""")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }

        val connector = createStreamConnector(sseBody)
        val events = connector.createMessageStream(simpleRequest("你好")).toList()

        val complete = events.filterIsInstance<LlmStreamEvent.MessageComplete>().first()
        assertEquals("你好，世界！🎉", complete.response.text())
    }

    // ========================================================================
    // 13. 请求 URL 和 Authorization header
    // ========================================================================

    @Test
    fun testRequestUrlAndAuth() = runTest {
        var capturedUrl = ""
        var capturedAuth = ""
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedAuth = request.headers[HttpHeaders.Authorization] ?: ""
            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(this@OpenAiConnectorTest.json) }
        }
        val connector = OpenAiConnector(apiKey = "sk-test123", modelId = "gpt-4o", httpClient = client)
        connector.createMessage(simpleRequest())

        assertTrue(capturedUrl.contains("chat/completions"))
        assertEquals("Bearer sk-test123", capturedAuth)
    }

    // ========================================================================
    // 14. 自定义 baseUrl（第三方中转兼容）
    // ========================================================================

    @Test
    fun testCustomBaseUrl() = runTest {
        var capturedUrl = ""
        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(this@OpenAiConnectorTest.json) }
        }
        val connector = OpenAiConnector(
            apiKey = "sk-test",
            baseUrl = "https://my-proxy.com/v1",
            httpClient = client
        )
        connector.createMessage(simpleRequest())

        assertTrue(capturedUrl.startsWith("https://my-proxy.com/v1/chat/completions"))
    }

    // ========================================================================
    // 15. 流式 — \r\n + [DONE] 终止
    // ========================================================================

    @Test
    fun testStreamCrLfAndDone() = runTest {
        val sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"index\":0,\"finish_reason\":\"stop\"}]}\r\n\r\ndata: [DONE]\r\n\r\n"

        val connector = createStreamConnector(sseBody)
        val events = connector.createMessageStream(simpleRequest()).toList()

        val textDeltas = events.filterIsInstance<LlmStreamEvent.TextDelta>()
        assertEquals(1, textDeltas.size)
        assertEquals("Hi", textDeltas[0].text)
    }

    // ========================================================================
    // 16. enableLogging 不抛异常
    // ========================================================================

    @Test
    fun testLoggingEnabled() = runTest {
        val responseJson = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
        val mockEngine = MockEngine {
            respond(content = responseJson, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(this@OpenAiConnectorTest.json) }
        }
        val connector = OpenAiConnector(apiKey = "test", enableLogging = true, httpClient = client)
        val response = connector.createMessage(simpleRequest())
        assertEquals("ok", response.text())
    }
}
