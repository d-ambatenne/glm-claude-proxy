package com.jetbrains.glmproxy.model.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MessagesResponse(
    val id: String,
    val type: String = "message",
    val role: String = "assistant",
    val content: List<ResponseContentBlock>,
    val model: String,
    @SerialName("stop_reason") val stopReason: String?,
    @SerialName("stop_sequence") val stopSequence: String? = null,
    val usage: Usage,
)

@Serializable
data class ResponseContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null,
)

@Serializable
data class Usage(
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
)
