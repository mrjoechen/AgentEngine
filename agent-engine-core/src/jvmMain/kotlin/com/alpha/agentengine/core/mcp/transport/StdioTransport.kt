package com.alpha.agentengine.core.mcp.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * Stdio Transport（JVM）。
 * 通过 stdin/stdout 进行 MCP 通信，每行一条 JSON-RPC 消息。
 * 适用于命令行工具或作为子进程被调用的场景。
 */
class StdioTransport(
    private val input: BufferedReader = BufferedReader(InputStreamReader(System.`in`)),
    private val output: PrintWriter = PrintWriter(System.out, true)
) : McpTransport {

    private val incomingChannel = Channel<String>(Channel.UNLIMITED)

    override suspend fun start() {
        // 在 IO 线程持续读取 stdin
        withContext(Dispatchers.IO) {
            try {
                var line = input.readLine()
                while (line != null) {
                    if (line.isNotBlank()) {
                        incomingChannel.send(line)
                    }
                    line = input.readLine()
                }
            } catch (_: Exception) {
                // stdin 关闭
            } finally {
                incomingChannel.close()
            }
        }
    }

    override suspend fun send(message: String) {
        withContext(Dispatchers.IO) {
            output.println(message)
            output.flush()
        }
    }

    override fun incoming(): Flow<String> = incomingChannel.receiveAsFlow()

    override suspend fun close() {
        incomingChannel.close()
    }
}
