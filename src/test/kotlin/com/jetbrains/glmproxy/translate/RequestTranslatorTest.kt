package com.jetbrains.glmproxy.translate

import com.jetbrains.glmproxy.model.anthropic.MessagesRequest
import com.jetbrains.glmproxy.model.anthropic.Tool
import com.jetbrains.glmproxy.server.proxyJson
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RequestTranslatorTest {

    @Test
    fun `simple text message`() {
        val request = MessagesRequest(
            model = "test-model",
            maxTokens = 100,
            messages = listOf(
                com.jetbrains.glmproxy.model.anthropic.Message("user", JsonPrimitive("Hello"))
            ),
        )
        val result = RequestTranslator.translate(request)

        assertEquals("test-model", result.model)
        assertEquals(100, result.maxTokens)
        assertEquals(1, result.messages.size)
        assertEquals("user", result.messages[0].role)
        assertEquals("Hello", result.messages[0].content?.jsonPrimitive?.content)
    }

    @Test
    fun `system string becomes system message`() {
        val request = MessagesRequest(
            model = "m",
            maxTokens = 100,
            messages = listOf(
                com.jetbrains.glmproxy.model.anthropic.Message("user", JsonPrimitive("Hi"))
            ),
            system = JsonPrimitive("You are helpful"),
        )
        val result = RequestTranslator.translate(request)

        assertEquals(2, result.messages.size)
        assertEquals("system", result.messages[0].role)
        assertEquals("You are helpful", result.messages[0].content?.jsonPrimitive?.content)
    }

    @Test
    fun `system as array of blocks`() {
        val system = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "Part 1") })
            add(buildJsonObject { put("type", "text"); put("text", "Part 2") })
        }
        val request = MessagesRequest(
            model = "m",
            maxTokens = 100,
            messages = listOf(
                com.jetbrains.glmproxy.model.anthropic.Message("user", JsonPrimitive("Hi"))
            ),
            system = system,
        )
        val result = RequestTranslator.translate(request)

        assertEquals("system", result.messages[0].role)
        assertEquals("Part 1\nPart 2", result.messages[0].content?.jsonPrimitive?.content)
    }

    @Test
    fun `tool definitions translate`() {
        val request = MessagesRequest(
            model = "m",
            maxTokens = 100,
            messages = listOf(
                com.jetbrains.glmproxy.model.anthropic.Message("user", JsonPrimitive("Hi"))
            ),
            tools = listOf(
                Tool(
                    name = "read_file",
                    description = "Read a file",
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("path", buildJsonObject { put("type", "string") })
                        })
                    },
                )
            ),
        )
        val result = RequestTranslator.translate(request)

        assertEquals(1, result.tools?.size)
        val tool = result.tools!![0]
        assertEquals("function", tool.type)
        assertEquals("read_file", tool.function.name)
        assertEquals("Read a file", tool.function.description)
    }

    @Test
    fun `assistant tool_use message`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "I'll read that file.")
            })
            add(buildJsonObject {
                put("type", "tool_use")
                put("id", "call_123")
                put("name", "read_file")
                put("input", buildJsonObject { put("path", "/tmp/test.txt") })
            })
        }
        val request = MessagesRequest(
            model = "m",
            maxTokens = 100,
            messages = listOf(
                com.jetbrains.glmproxy.model.anthropic.Message("assistant", content)
            ),
        )
        val result = RequestTranslator.translate(request)

        val msg = result.messages[0]
        assertEquals("assistant", msg.role)
        assertEquals("I'll read that file.", msg.content?.jsonPrimitive?.content)
        assertEquals(1, msg.toolCalls?.size)
        val tc = msg.toolCalls!!
        assertEquals("call_123", tc[0].id)
        assertEquals("read_file", tc[0].function.name)
    }

    @Test
    fun `user tool_result splits into tool and user messages`() {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", "call_123")
                put("content", "file contents here")
            })
        }
        val request = MessagesRequest(
            model = "m",
            maxTokens = 100,
            messages = listOf(
                com.jetbrains.glmproxy.model.anthropic.Message("user", content)
            ),
        )
        val result = RequestTranslator.translate(request)

        assertEquals(1, result.messages.size)
        assertEquals("tool", result.messages[0].role)
        assertEquals("call_123", result.messages[0].toolCallId)
        assertEquals("file contents here", result.messages[0].content?.jsonPrimitive?.content)
    }

    @Test
    fun `tool_choice auto maps to auto string`() {
        val request = MessagesRequest(
            model = "m",
            maxTokens = 100,
            messages = listOf(
                com.jetbrains.glmproxy.model.anthropic.Message("user", JsonPrimitive("Hi"))
            ),
            toolChoice = buildJsonObject { put("type", "auto") },
        )
        val result = RequestTranslator.translate(request)
        assertEquals(JsonPrimitive("auto"), result.toolChoice)
    }

    @Test
    fun `tool_choice any maps to required`() {
        val request = MessagesRequest(
            model = "m",
            maxTokens = 100,
            messages = listOf(
                com.jetbrains.glmproxy.model.anthropic.Message("user", JsonPrimitive("Hi"))
            ),
            toolChoice = buildJsonObject { put("type", "any") },
        )
        val result = RequestTranslator.translate(request)
        assertEquals(JsonPrimitive("required"), result.toolChoice)
    }

    @Test
    fun `tool_choice specific tool`() {
        val request = MessagesRequest(
            model = "m",
            maxTokens = 100,
            messages = listOf(
                com.jetbrains.glmproxy.model.anthropic.Message("user", JsonPrimitive("Hi"))
            ),
            toolChoice = buildJsonObject { put("type", "tool"); put("name", "read_file") },
        )
        val result = RequestTranslator.translate(request)
        val tc = result.toolChoice as JsonObject
        assertEquals("function", tc["type"]?.jsonPrimitive?.content)
        assertEquals("read_file", tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `stop sequences translate`() {
        val request = MessagesRequest(
            model = "m",
            maxTokens = 100,
            messages = listOf(
                com.jetbrains.glmproxy.model.anthropic.Message("user", JsonPrimitive("Hi"))
            ),
            stopSequences = listOf("END", "STOP"),
        )
        val result = RequestTranslator.translate(request)
        val stop = result.stop as JsonArray
        assertEquals(2, stop.size)
        assertEquals("END", stop[0].jsonPrimitive.content)
    }

    @Test
    fun `temperature and top_p pass through`() {
        val request = MessagesRequest(
            model = "m",
            maxTokens = 100,
            messages = listOf(
                com.jetbrains.glmproxy.model.anthropic.Message("user", JsonPrimitive("Hi"))
            ),
            temperature = 0.7,
            topP = 0.9,
        )
        val result = RequestTranslator.translate(request)
        assertEquals(0.7, result.temperature)
        assertEquals(0.9, result.topP)
    }

    @Test
    fun `full multi-turn tool use conversation`() {
        val request = proxyJson.decodeFromString<MessagesRequest>("""
        {
            "model": "test",
            "max_tokens": 1024,
            "tools": [{"name": "get_weather", "description": "Get weather", "input_schema": {"type": "object", "properties": {"city": {"type": "string"}}}}],
            "messages": [
                {"role": "user", "content": "What's the weather in London?"},
                {"role": "assistant", "content": [
                    {"type": "text", "text": "Let me check."},
                    {"type": "tool_use", "id": "tc_1", "name": "get_weather", "input": {"city": "London"}}
                ]},
                {"role": "user", "content": [
                    {"type": "tool_result", "tool_use_id": "tc_1", "content": "Sunny, 22°C"}
                ]},
                {"role": "assistant", "content": "It's sunny and 22°C in London."}
            ]
        }
        """)
        val result = RequestTranslator.translate(request)

        assertEquals(4, result.messages.size)
        assertEquals("user", result.messages[0].role)
        assertEquals("assistant", result.messages[1].role)
        assertEquals("tool", result.messages[2].role)
        assertEquals("tc_1", result.messages[2].toolCallId)
        assertEquals("assistant", result.messages[3].role)
    }
}
