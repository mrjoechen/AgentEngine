package com.alpha.agentengine.llm.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ========== SSE Stream Event Types ==========

@Serializable
internal data class StreamMessageStart(
    val message: AnthropicResponse
)

@Serializable
internal data class StreamContentBlockStart(
    val index: Int,
    @SerialName("content_block") val contentBlock: StreamContentBlock
)

@Serializable
internal sealed class StreamContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String = "") : StreamContentBlock()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject = JsonObject(emptyMap())
    ) : StreamContentBlock()
}

@Serializable
internal data class StreamContentBlockDelta(
    val index: Int,
    val delta: StreamDelta
)

@Serializable
internal sealed class StreamDelta {
    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val text: String) : StreamDelta()

    @Serializable
    @SerialName("input_json_delta")
    data class InputJsonDelta(
        @SerialName("partial_json") val partialJson: String
    ) : StreamDelta()
}

@Serializable
internal data class StreamContentBlockStop(
    val index: Int
)

@Serializable
internal data class StreamMessageDelta(
    val delta: MessageDeltaBody,
    val usage: StreamDeltaUsage? = null
)

@Serializable
internal data class MessageDeltaBody(
    @SerialName("stop_reason") val stopReason: String? = null
)

@Serializable
internal data class StreamDeltaUsage(
    @SerialName("output_tokens") val outputTokens: Int
)
