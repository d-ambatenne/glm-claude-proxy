package com.jetbrains.glmproxy.translate

import com.jetbrains.glmproxy.model.anthropic.SseEvent
import com.jetbrains.glmproxy.model.openai.ChatCompletionChunk
import com.jetbrains.glmproxy.model.openai.ChatUsage
import com.jetbrains.glmproxy.model.openai.ChunkToolCall
import com.jetbrains.glmproxy.server.proxyJson
import kotlinx.serialization.json.*
import java.time.Instant

/** JSON line persisted per completed thinking block. */
internal fun thinkingBlockJson(text: String, model: String): JsonObject = buildJsonObject {
    put("type", "thinking")
    put("thinking", text)
    put("model", model)
    put("saved_at", Instant.now().toString())
}

class StreamTranslator(
    private val requestModel: String,
    private val messageId: String,
) {
    private var started = false
    private var currentContentIndex = 0
    private var textBlockOpen = false
    private var thinkingBlockOpen = false
    private val thinkingBuffer = StringBuilder()
    private val thinkingBlocks = mutableListOf<JsonObject>()
    private val activeToolCalls = mutableMapOf<Int, ToolCallAccumulator>()
    // stop_reason/usage of the finished turn. Anthropic requires message_delta
    // (which carries them) after all content_block_stop events, but OpenAI sends
    // usage on a separate trailing chunk with empty choices, so they are stashed
    // here and emitted by finish() instead of on the finish_reason chunk.
    private var pendingStopReason: String? = null
    private var pendingUsage: ChatUsage? = null
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
            // usage-only chunk at the end of stream: keep the authoritative numbers
            if (chunk.usage != null) {
                pendingUsage = chunk.usage
            }
            return events
        }
        val delta = choice.delta

        if (!started) {
            events.add(messageStartEvent())
            started = true
        }

        delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let { reasoning ->
            if (!thinkingBlockOpen) {
                closeTextBlock(events)
                events.add(contentBlockStart(currentContentIndex, thinkingBlock()))
                thinkingBlockOpen = true
            }
            thinkingBuffer.append(reasoning)
            events.add(thinkingDelta(currentContentIndex, reasoning))
        }

        if (delta.content != null) {
            closeThinkingBlock(events)
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
            events.addAll(stashFinish(choice.finishReason, chunk.usage))
        }

        return events
    }

    /** Emits message_delta/message_stop for a stream that has ended. Idempotent. */
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

        closeThinkingBlock(events)
        closeTextBlock(events)
        for ((_, acc) in activeToolCalls) {
            events.add(contentBlockStop(acc.contentIndex))
        }
        activeToolCalls.clear()

        events.add(messageDelta(pendingStopReason ?: "end_turn", pendingUsage))
        events.add(messageStop())
        finished = true
        return events
    }

    /** Thinking blocks completed during the stream, for local persistence. */
    fun collectedThinkingBlocks(): List<JsonObject> = thinkingBlocks.toList()

    private fun processToolCallDelta(tc: ChunkToolCall): List<SseEvent> {
        val events = mutableListOf<SseEvent>()

        if (tc.id != null) {
            closeTextBlock(events)
            closeThinkingBlock(events)

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

    /**
     * Records the stop reason and usage of the finished turn. The message_delta
     * carrying them is deferred to finish() so usage from the trailing usage-only
     * chunk (sent after finish_reason) is included.
     */
    private fun stashFinish(finishReason: String, usage: ChatUsage?): List<SseEvent> {
        val events = mutableListOf<SseEvent>()

        closeThinkingBlock(events)
        closeTextBlock(events)
        for ((_, acc) in activeToolCalls) {
            events.add(contentBlockStop(acc.contentIndex))
        }
        activeToolCalls.clear()

        pendingStopReason = when (finishReason) {
            "stop" -> "end_turn"
            "tool_calls" -> "tool_use"
            "length" -> "max_tokens"
            else -> "end_turn"
        }
        if (usage != null) pendingUsage = usage

        return events
    }

    private fun closeTextBlock(events: MutableList<SseEvent>) {
        if (!textBlockOpen) return
        events.add(contentBlockStop(currentContentIndex))
        currentContentIndex++
        textBlockOpen = false
    }

    private fun closeThinkingBlock(events: MutableList<SseEvent>) {
        if (!thinkingBlockOpen) return
        events.add(contentBlockStop(currentContentIndex))
        thinkingBlocks.add(thinkingBlockJson(thinkingBuffer.toString(), requestModel))
        thinkingBuffer.clear()
        currentContentIndex++
        thinkingBlockOpen = false
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

    private fun thinkingDelta(index: Int, text: String): SseEvent {
        val data = buildJsonObject {
            put("type", "content_block_delta")
            put("index", index)
            put("delta", buildJsonObject {
                put("type", "thinking_delta")
                put("thinking", text)
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
        usage: ChatUsage?,
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

    private fun thinkingBlock(): JsonObject = buildJsonObject {
        put("type", "thinking")
        put("thinking", "")
    }

    private fun toolUseBlock(id: String, name: String): JsonObject = buildJsonObject {
        put("type", "tool_use")
        put("id", id)
        put("name", name)
        put("input", buildJsonObject {})
    }
}
