package com.jetbrains.glmproxy.model.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<Message>,
    val system: JsonElement? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("stop_sequences") val stopSequences: List<String>? = null,
    val stream: Boolean? = null,
    val tools: List<Tool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class Message(
    val role: String,
    val content: JsonElement,
)

@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val content: JsonElement? = null,
    @SerialName("is_error") val isError: Boolean? = null,
    val source: ImageSource? = null,
)

@Serializable
data class ImageSource(
    val type: String,
    @SerialName("media_type") val mediaType: String,
    val data: String,
)

@Serializable
data class Tool(
    val name: String,
    val description: String? = null,
    @SerialName("input_schema") val inputSchema: JsonObject,
)
