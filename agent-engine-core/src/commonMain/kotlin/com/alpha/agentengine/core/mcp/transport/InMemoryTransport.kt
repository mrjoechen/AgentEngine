package com.alpha.agentengine.core.mcp.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 内存 Transport，用于测试和进程内 MCP 通信。
 * 创建一对 InMemoryTransport，一端的 send 会到达另一端的 incoming。
 */
class InMemoryTransport private constructor(
    private val sendChannel: Channel<String>,
    private val receiveChannel: Channel<String>
) : McpTransport {

    override suspend fun start() {}

    override suspend fun send(message: String) {
        sendChannel.send(message)
    }

    override fun incoming(): Flow<String> = receiveChannel.receiveAsFlow()

    override suspend fun close() {
        sendChannel.close()
        receiveChannel.close()
    }

    companion object {
        /**
         * 创建一对互联的 Transport。
         * serverTransport 的 send → clientTransport 的 incoming
         * clientTransport 的 send → serverTransport 的 incoming
         */
        fun createPair(): Pair<InMemoryTransport, InMemoryTransport> {
            val channel1 = Channel<String>(Channel.UNLIMITED)
            val channel2 = Channel<String>(Channel.UNLIMITED)
            val server = InMemoryTransport(sendChannel = channel1, receiveChannel = channel2)
            val client = InMemoryTransport(sendChannel = channel2, receiveChannel = channel1)
            return server to client
        }
    }
}
