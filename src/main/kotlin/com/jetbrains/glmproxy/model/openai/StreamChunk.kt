package com.jetbrains.glmproxy.model.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val choices: List<ChunkChoice> = emptyList(),
    val model: String? = null,
    val usage: ChatUsage? = null,
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChunkToolCall>? = null,
)

@Serializable
data class ChunkToolCall(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: ChunkFunctionCall? = null,
)

@Serializable
data class ChunkFunctionCall(
    val name: String? = null,
    val arguments: String? = null,
)
