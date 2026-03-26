package com.alpha.agentengine.mcp

import com.alpha.agentengine.core.mcp.transport.McpTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * SSE Transport（Ktor 服务端）。
 *
 * 通信模型:
 * - Server → Client: 通过 SSE (Server-Sent Events) 推送
 * - Client → Server: 通过 HTTP POST 发送
 *
 * 每个连接对应一个 SseTransport 实例。
 */
class SseTransport : McpTransport {
    /** Client → Server: POST 消息写入此 channel */
    private val incomingChannel = Channel<String>(Channel.UNLIMITED)

    /** Server → Client: SSE 推送消息从此 channel 读取 */
    val outgoingChannel = Channel<String>(Channel.UNLIMITED)

    override suspend fun start() {}

    override suspend fun send(message: String) {
        outgoingChannel.send(message)
    }

    override fun incoming(): Flow<String> = incomingChannel.receiveAsFlow()

    override suspend fun close() {
        incomingChannel.close()
        outgoingChannel.close()
    }

    /**
     * 将 Client POST 的消息注入到 incoming 流。
     * 由 Ktor 路由调用。
     */
    suspend fun receiveFromClient(message: String) {
        incomingChannel.send(message)
    }
}
