package com.alpha.agentengine.llm.gemini

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ========== Request Models ==========
// Gemini REST API 全部使用 camelCase，Kotlin 属性名与 JSON 字段名一致，无需 @SerialName

@Serializable
internal data class GeminiRequest(
    val contents: List<GeminiContent>,
    val tools: List<GeminiTool>? = null,
    val toolConfig: GeminiToolConfig? = null,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GeminiGenerationConfig? = null
)

@Serializable
internal data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>
)

@Serializable
internal data class GeminiPart(
    val text: String? = null,
    val functionCall: GeminiFunctionCall? = null,
    val functionResponse: GeminiFunctionResponse? = null
)

@Serializable
internal data class GeminiFunctionCall(
    val name: String,
    val args: JsonObject? = null
)

@Serializable
internal data class GeminiFunctionResponse(
    val name: String,
    val response: JsonObject
)

@Serializable
internal data class GeminiTool(
    val functionDeclarations: List<GeminiFunctionDeclaration>
)

@Serializable
internal data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonObject? = null
)

@Serializable
internal data class GeminiToolConfig(
    val functionCallingConfig: GeminiFunctionCallingConfig? = null
)

@Serializable
internal data class GeminiFunctionCallingConfig(
    val mode: String = "AUTO" // AUTO, ANY, NONE
)

@Serializable
internal data class GeminiGenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val candidateCount: Int? = null
)

// ========== Response Models ==========

@Serializable
internal data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val usageMetadata: GeminiUsageMetadata? = null,
    val modelVersion: String? = null,
    val responseId: String? = null,
    val error: GeminiErrorDetail? = null
)

@Serializable
internal data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
    val index: Int? = null,
    val safetyRatings: List<JsonElement>? = null
)

@Serializable
internal data class GeminiUsageMetadata(
    val promptTokenCount: Int = 0,
    val candidatesTokenCount: Int = 0,
    val totalTokenCount: Int = 0
)

// ========== Error ==========

@Serializable
internal data class GeminiErrorResponse(
    val error: GeminiErrorDetail
)

@Serializable
internal data class GeminiErrorDetail(
    val code: Int = 0,
    val message: String = "",
    val status: String = ""
)
