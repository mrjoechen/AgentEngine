package com.alpha.agentengine.mcp

import com.alpha.agentengine.core.mcp.server.McpServer
import com.alpha.agentengine.core.tool.ToolRegistry
import java.util.UUID
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.util.concurrent.ConcurrentHashMap

/**
 * 配置 MCP SSE 路由。
 *
 * 端点:
 * - GET  /mcp/sse           SSE 连接（Server→Client 推送）
 * - POST /mcp/message       Client→Server 发送 JSON-RPC 消息
 *
 * 用法:
 * ```
 * fun Application.module() {
 *     install(SSE)
 *     configureMcp(toolRegistry, serverName = "my-agent")
 * }
 * ```
 */
fun Application.configureMcp(
    toolRegistry: ToolRegistry,
    serverName: String = "AgentEngine",
    serverVersion: String = "1.0.0"
) {
    val sessions = ConcurrentHashMap<String, SseTransport>()

    routing {
        // SSE 端点：建立 Server → Client 的推送通道
        sse("/mcp/sse") {
            val sessionId = UUID.randomUUID().toString()
            val transport = SseTransport()
            sessions[sessionId] = transport

            // 启动 MCP Server 处理该连接
            val server = McpServer.builder()
                .name(serverName)
                .version(serverVersion)
                .toolRegistry(toolRegistry)
                .transport(transport)
                .build()

            val serverJob = server.start(this)

            // 发送 endpoint 事件，告知 Client POST 地址
            send(ServerSentEvent(
                data = "/mcp/message?sessionId=$sessionId",
                event = "endpoint"
            ))

            // 持续将 outgoing 消息通过 SSE 推送给 Client
            try {
                for (message in transport.outgoingChannel) {
                    send(ServerSentEvent(data = message, event = "message"))
                }
            } catch (_: ClosedReceiveChannelException) {
                // channel 关闭
            } finally {
                server.stop()
                sessions.remove(sessionId)
            }
        }

        // POST 端点：接收 Client → Server 的 JSON-RPC 消息
        post("/mcp/message") {
            val sessionId = call.request.queryParameters["sessionId"]
            if (sessionId == null) {
                call.respond(HttpStatusCode.BadRequest, "Missing sessionId")
                return@post
            }

            val transport = sessions[sessionId]
            if (transport == null) {
                call.respond(HttpStatusCode.NotFound, "Session not found")
                return@post
            }

            val body = call.receiveText()
            transport.receiveFromClient(body)
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
