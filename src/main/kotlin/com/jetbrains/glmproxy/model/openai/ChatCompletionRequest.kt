package com.jetbrains.glmproxy.model.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val stop: JsonElement? = null,
    val stream: Boolean? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    val tools: List<ChatTool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
)

@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
    // GLM/vLLM models return the thinking phase here; parsed so it can be logged,
    // never sent back upstream (nulls are omitted on outgoing requests).
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class ChatTool(
    val type: String = "function",
    val function: FunctionDef,
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject,
)
