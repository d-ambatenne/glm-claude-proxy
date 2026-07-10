package com.jetbrains.glmproxy.translate

import com.jetbrains.glmproxy.model.openai.*
import com.jetbrains.glmproxy.server.proxyJson
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamTranslatorTest {

    private fun translator() = StreamTranslator("test-model", "msg_test123")

    private fun textChunk(content: String, role: String? = null, finishReason: String? = null) =
        ChatCompletionChunk(
            id = "chunk_1",
            choices = listOf(
                ChunkChoice(
                    delta = ChunkDelta(role = role, content = content),
                    finishReason = finishReason,
                )
            ),
        )

    private fun finishChunk(reason: String, usage: ChatUsage? = null) =
        ChatCompletionChunk(
            id = "chunk_1",
            choices = listOf(
                ChunkChoice(
                    delta = ChunkDelta(),
                    finishReason = reason,
                )
            ),
            usage = usage,
        )

    @Test
    fun `first text chunk emits message_start and content_block_start and delta`() {
        val t = translator()
        val events = t.processChunk(textChunk("Hello", role = "assistant"))

        assertEquals(3, events.size)
        assertEquals("message_start", events[0].type)
        assertEquals("content_block_start", events[1].type)
        assertEquals("content_block_delta", events[2].type)

        val deltaData = proxyJson.parseToJsonElement(events[2].data).jsonObject
        assertEquals("text_delta", deltaData["delta"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("Hello", deltaData["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun `subsequent text chunks emit only deltas`() {
        val t = translator()
        t.processChunk(textChunk("Hello", role = "assistant"))
        val events = t.processChunk(textChunk(" world"))

        assertEquals(1, events.size)
        assertEquals("content_block_delta", events[0].type)
    }

    @Test
    fun `finish chunk closes blocks and emits message_delta and message_stop`() {
        val t = translator()
        t.processChunk(textChunk("Hi", role = "assistant"))
        val events = t.processChunk(finishChunk("stop"))

        val types = events.map { it.type }
        assertTrue("content_block_stop" in types)
        assertTrue("message_delta" in types)
        assertTrue("message_stop" in types)

        val msgDelta = events.first { it.type == "message_delta" }
        val data = proxyJson.parseToJsonElement(msgDelta.data).jsonObject
        assertEquals("end_turn", data["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.content)
    }

    @Test
    fun `tool call chunks produce correct events`() {
        val t = translator()

        // First chunk: role
        val firstEvents = t.processChunk(
            ChatCompletionChunk(
                id = "c1",
                choices = listOf(
                    ChunkChoice(
                        delta = ChunkDelta(role = "assistant"),
                    )
                ),
            )
        )
        assertEquals("message_start", firstEvents[0].type)

        // Tool call start
        val toolStartEvents = t.processChunk(
            ChatCompletionChunk(
                id = "c1",
                choices = listOf(
                    ChunkChoice(
                        delta = ChunkDelta(
                            toolCalls = listOf(
                                ChunkToolCall(
                                    index = 0,
                                    id = "call_abc",
                                    type = "function",
                                    function = ChunkFunctionCall(name = "read_file", arguments = "{\"pa"),
                                )
                            )
                        ),
                    )
                ),
            )
        )

        val startTypes = toolStartEvents.map { it.type }
        assertTrue("content_block_start" in startTypes)
        assertTrue("content_block_delta" in startTypes)

        val blockStart = toolStartEvents.first { it.type == "content_block_start" }
        val blockData = proxyJson.parseToJsonElement(blockStart.data).jsonObject
        assertEquals("tool_use", blockData["content_block"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("call_abc", blockData["content_block"]?.jsonObject?.get("id")?.jsonPrimitive?.content)

        // Tool call continuation
        val contEvents = t.processChunk(
            ChatCompletionChunk(
                id = "c1",
                choices = listOf(
                    ChunkChoice(
                        delta = ChunkDelta(
                            toolCalls = listOf(
                                ChunkToolCall(
                                    index = 0,
                                    function = ChunkFunctionCall(arguments = "th\":\"/tmp\"}"),
                                )
                            )
                        ),
                    )
                ),
            )
        )
        assertEquals(1, contEvents.size)
        assertEquals("content_block_delta", contEvents[0].type)
        val contData = proxyJson.parseToJsonElement(contEvents[0].data).jsonObject
        assertEquals("input_json_delta", contData["delta"]?.jsonObject?.get("type")?.jsonPrimitive?.content)

        // Finish with tool_calls
        val finishEvents = t.processChunk(finishChunk("tool_calls"))
        val finishTypes = finishEvents.map { it.type }
        assertTrue("content_block_stop" in finishTypes)
        assertTrue("message_delta" in finishTypes)
        assertTrue("message_stop" in finishTypes)

        val msgDelta = finishEvents.first { it.type == "message_delta" }
        val md = proxyJson.parseToJsonElement(msgDelta.data).jsonObject
        assertEquals("tool_use", md["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.content)
    }

    @Test
    fun `finish produces events even without prior chunks`() {
        val t = translator()
        val events = t.finish()
        val types = events.map { it.type }
        assertTrue("message_start" in types)
        assertTrue("message_stop" in types)
    }

    @Test
    fun `text then tool call closes text block first`() {
        val t = translator()
        t.processChunk(textChunk("Thinking...", role = "assistant"))

        val events = t.processChunk(
            ChatCompletionChunk(
                id = "c1",
                choices = listOf(
                    ChunkChoice(
                        delta = ChunkDelta(
                            toolCalls = listOf(
                                ChunkToolCall(
                                    index = 0,
                                    id = "call_1",
                                    type = "function",
                                    function = ChunkFunctionCall(name = "search"),
                                )
                            )
                        ),
                    )
                ),
            )
        )

        val types = events.map { it.type }
        val stopIdx = types.indexOf("content_block_stop")
        val startIdx = types.indexOf("content_block_start")
        assertTrue(stopIdx < startIdx, "text block should be stopped before tool block starts")
    }
}
