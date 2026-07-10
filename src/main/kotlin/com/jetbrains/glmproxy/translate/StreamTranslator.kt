package com.jetbrains.glmproxy.translate

import com.jetbrains.glmproxy.model.anthropic.SseEvent
import com.jetbrains.glmproxy.model.openai.ChatCompletionChunk
import com.jetbrains.glmproxy.model.openai.ChunkToolCall
import com.jetbrains.glmproxy.server.proxyJson
import kotlinx.serialization.json.*

class StreamTranslator(
    private val requestModel: String,
    private val messageId: String,
) {
    private var started = false
    private var currentContentIndex = 0
    private var textBlockOpen = false
    private val activeToolCalls = mutableMapOf<Int, ToolCallAccumulator>()
    private var finished = false

    private data class ToolCallAccumulator(
        val id: String,
        val name: String,
        val arguments: StringBuilder = StringBuilder(),
        val contentIndex: Int,
    )

    fun processChunk(chunk: ChatCompletionChunk): List<SseEvent> {
        if (finished) return emptyList()
        val events = mutableListOf<SseEvent>()
        val choice = chunk.choices.firstOrNull() ?: run {
            if (chunk.usage != null) {
                // usage-only chunk at the end of stream
            }
            return events
        }
        val delta = choice.delta

        if (!started) {
            events.add(messageStartEvent())
            started = true
        }

        if (delta.content != null) {
            if (!textBlockOpen) {
                events.add(contentBlockStart(currentContentIndex, textBlock()))
                textBlockOpen = true
            }
            events.add(textDelta(currentContentIndex, delta.content))
        }

        delta.toolCalls?.forEach { tc ->
            events.addAll(processToolCallDelta(tc))
        }

        if (choice.finishReason != null) {
            events.addAll(finishWithReason(choice.finishReason, chunk.usage))
        }

        return events
    }

    fun finish(): List<SseEvent> {
        if (finished) return emptyList()
        val events = mutableListOf<SseEvent>()

        if (!started) {
            events.add(messageStartEvent())
            events.add(contentBlockStart(0, textBlock()))
            events.add(contentBlockStop(0))
            events.add(messageDelta("end_turn", null))
            events.add(messageStop())
            finished = true
            return events
        }

        if (textBlockOpen) {
            events.add(contentBlockStop(currentContentIndex))
            textBlockOpen = false
        }
        for ((_, acc) in activeToolCalls) {
            events.add(contentBlockStop(acc.contentIndex))
        }
        activeToolCalls.clear()

        events.add(messageDelta("end_turn", null))
        events.add(messageStop())
        finished = true
        return events
    }

    private fun processToolCallDelta(tc: ChunkToolCall): List<SseEvent> {
        val events = mutableListOf<SseEvent>()

        if (tc.id != null) {
            if (textBlockOpen) {
                events.add(contentBlockStop(currentContentIndex))
                currentContentIndex++
                textBlockOpen = false
            }

            val name = tc.function?.name ?: ""
            val acc = ToolCallAccumulator(
                id = tc.id,
                name = name,
                contentIndex = currentContentIndex,
            )
            activeToolCalls[tc.index] = acc

            events.add(contentBlockStart(currentContentIndex, toolUseBlock(tc.id, name)))

            tc.function?.arguments?.let { args ->
                if (args.isNotEmpty()) {
                    acc.arguments.append(args)
                    events.add(inputJsonDelta(currentContentIndex, args))
                }
            }
            currentContentIndex++
        } else {
            val acc = activeToolCalls[tc.index] ?: return events
            tc.function?.arguments?.let { args ->
                if (args.isNotEmpty()) {
                    acc.arguments.append(args)
                    events.add(inputJsonDelta(acc.contentIndex, args))
                }
            }
        }

        return events
    }

    private fun finishWithReason(
        finishReason: String,
        usage: com.jetbrains.glmproxy.model.openai.ChatUsage?,
    ): List<SseEvent> {
        val events = mutableListOf<SseEvent>()

        if (textBlockOpen) {
            events.add(contentBlockStop(currentContentIndex))
            textBlockOpen = false
        }
        for ((_, acc) in activeToolCalls) {
            events.add(contentBlockStop(acc.contentIndex))
        }
        activeToolCalls.clear()

        val stopReason = when (finishReason) {
            "stop" -> "end_turn"
            "tool_calls" -> "tool_use"
            "length" -> "max_tokens"
            else -> "end_turn"
        }

        events.add(messageDelta(stopReason, usage))
        events.add(messageStop())
        finished = true
        return events
    }

    // SSE event builders

    private fun messageStartEvent(): SseEvent {
        val data = buildJsonObject {
            put("type", "message_start")
            put("message", buildJsonObject {
                put("id", messageId)
                put("type", "message")
                put("role", "assistant")
                put("content", buildJsonArray {})
                put("model", requestModel)
                put("stop_reason", JsonNull)
                put("stop_sequence", JsonNull)
                put("usage", buildJsonObject {
                    put("input_tokens", 0)
                    put("output_tokens", 0)
                })
            })
        }
        return SseEvent("message_start", proxyJson.encodeToString(JsonElement.serializer(), data))
    }

    private fun contentBlockStart(index: Int, block: JsonObject): SseEvent {
        val data = buildJsonObject {
            put("type", "content_block_start")
            put("index", index)
            put("content_block", block)
        }
        return SseEvent("content_block_start", proxyJson.encodeToString(JsonElement.serializer(), data))
    }

    private fun textDelta(index: Int, text: String): SseEvent {
        val data = buildJsonObject {
            put("type", "content_block_delta")
            put("index", index)
            put("delta", buildJsonObject {
                put("type", "text_delta")
                put("text", text)
            })
        }
        return SseEvent("content_block_delta", proxyJson.encodeToString(JsonElement.serializer(), data))
    }

    private fun inputJsonDelta(index: Int, partialJson: String): SseEvent {
        val data = buildJsonObject {
            put("type", "content_block_delta")
            put("index", index)
            put("delta", buildJsonObject {
                put("type", "input_json_delta")
                put("partial_json", partialJson)
            })
        }
        return SseEvent("content_block_delta", proxyJson.encodeToString(JsonElement.serializer(), data))
    }

    private fun contentBlockStop(index: Int): SseEvent {
        val data = buildJsonObject {
            put("type", "content_block_stop")
            put("index", index)
        }
        return SseEvent("content_block_stop", proxyJson.encodeToString(JsonElement.serializer(), data))
    }

    private fun messageDelta(
        stopReason: String,
        usage: com.jetbrains.glmproxy.model.openai.ChatUsage?,
    ): SseEvent {
        val data = buildJsonObject {
            put("type", "message_delta")
            put("delta", buildJsonObject {
                put("stop_reason", stopReason)
            })
            put("usage", buildJsonObject {
                put("output_tokens", usage?.completionTokens ?: 0)
            })
        }
        return SseEvent("message_delta", proxyJson.encodeToString(JsonElement.serializer(), data))
    }

    private fun messageStop(): SseEvent {
        return SseEvent("message_stop", """{"type":"message_stop"}""")
    }

    private fun textBlock(): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", "")
    }

    private fun toolUseBlock(id: String, name: String): JsonObject = buildJsonObject {
        put("type", "tool_use")
        put("id", id)
        put("name", name)
        put("input", buildJsonObject {})
    }
}
