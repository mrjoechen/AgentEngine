package com.alpha.agentengine.core.mcp.transport

import kotlinx.coroutines.flow.Flow

/**
 * MCP 传输层抽象。
 * 负责 JSON-RPC 消息的收发，不关心消息内容。
 * 每条消息是一个完整的 JSON 字符串。
 */
interface McpTransport {
    /**
     * 启动传输层，开始接收消息。
     */
    suspend fun start()

    /**
     * 发送一条 JSON-RPC 消息。
     */
    suspend fun send(message: String)

    /**
     * 接收传入的 JSON-RPC 消息流。
     */
    fun incoming(): Flow<String>

    /**
     * 关闭传输层。
     */
    suspend fun close()
}
