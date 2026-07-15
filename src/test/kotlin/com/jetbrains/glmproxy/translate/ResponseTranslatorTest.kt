package com.jetbrains.glmproxy.translate

import com.jetbrains.glmproxy.model.anthropic.MessagesResponse
import com.jetbrains.glmproxy.model.anthropic.ResponseContentBlock
import com.jetbrains.glmproxy.model.anthropic.Usage
import com.jetbrains.glmproxy.model.openai.*
import com.jetbrains.glmproxy.server.proxyJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseTranslatorTest {

    @Test
    fun `text response maps to text content block`() {
        val response = ChatCompletionResponse(
            id = "resp_1",
            choices = listOf(
                Choice(
                    message = ChatMessage(role = "assistant", content = JsonPrimitive("Hello!")),
                    finishReason = "stop",
                )
            ),
            model = "glm",
            usage = ChatUsage(promptTokens = 10, completionTokens = 5),
        )
        val result = ResponseTranslator.translate(response, "my-model")

        assertEquals("msg_resp_1", result.id)
        assertEquals("my-model", result.model)
        assertEquals(1, result.content.size)
        assertEquals("text", result.content[0].type)
        assertEquals("Hello!", result.content[0].text)
        assertEquals("end_turn", result.stopReason)
        assertEquals(10, result.usage.inputTokens)
        assertEquals(5, result.usage.outputTokens)
    }

    @Test
    fun `tool_calls response maps to tool_use blocks`() {
        val response = ChatCompletionResponse(
            id = "resp_2",
            choices = listOf(
                Choice(
                    message = ChatMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            ToolCall(
                                id = "call_abc",
                                function = FunctionCall(
                                    name = "read_file",
                                    arguments = """{"path":"/tmp/test.txt"}""",
                                ),
                            )
                        ),
                    ),
                    finishReason = "tool_calls",
                )
            ),
            model = "glm",
            usage = ChatUsage(promptTokens = 20, completionTokens = 15),
        )
        val result = ResponseTranslator.translate(response, "my-model")

        assertEquals("tool_use", result.stopReason)
        assertEquals(1, result.content.size)
        assertEquals("tool_use", result.content[0].type)
        assertEquals("call_abc", result.content[0].id)
        assertEquals("read_file", result.content[0].name)
    }

    @Test
    fun `finish_reason length maps to max_tokens`() {
        val response = ChatCompletionResponse(
            id = "resp_3",
            choices = listOf(
                Choice(
                    message = ChatMessage(role = "assistant", content = JsonPrimitive("truncated")),
                    finishReason = "length",
                )
            ),
            model = "glm",
        )
        val result = ResponseTranslator.translate(response, "m")
        assertEquals("max_tokens", result.stopReason)
    }

    @Test
    fun `empty choices produce empty response`() {
        val response = ChatCompletionResponse(
            id = "resp_4",
            choices = emptyList(),
            model = "glm",
        )
        val result = ResponseTranslator.translate(response, "m")
        assertEquals(1, result.content.size)
        assertEquals("text", result.content[0].type)
        assertEquals("", result.content[0].text)
    }

    @Test
    fun `text and tool_calls together`() {
        val response = ChatCompletionResponse(
            id = "resp_5",
            choices = listOf(
                Choice(
                    message = ChatMessage(
                        role = "assistant",
                        content = JsonPrimitive("Let me check."),
                        toolCalls = listOf(
                            ToolCall(
                                id = "call_1",
                                function = FunctionCall(name = "search", arguments = """{"q":"test"}"""),
                            )
                        ),
                    ),
                    finishReason = "tool_calls",
                )
            ),
            model = "glm",
            usage = ChatUsage(promptTokens = 5, completionTokens = 10),
        )
        val result = ResponseTranslator.translate(response, "m")

        assertEquals(2, result.content.size)
        assertEquals("text", result.content[0].type)
        assertEquals("tool_use", result.content[1].type)
        assertEquals("tool_use", result.stopReason)
    }

    @Test
    fun `null content with no tool calls produces empty text block`() {
        val response = ChatCompletionResponse(
            id = "resp_6",
            choices = listOf(
                Choice(
                    message = ChatMessage(role = "assistant", content = null),
                    finishReason = "stop",
                )
            ),
            model = "glm",
        )
        val result = ResponseTranslator.translate(response, "m")
        assertEquals(1, result.content.size)
        assertEquals("text", result.content[0].type)
        assertEquals("", result.content[0].text)
    }

    /**
     * Regression: the non-streaming response is serialized with proxyJson, which
     * must encode default-valued fields. type="message" and role="assistant" are
     * defaults on MessagesResponse; if encodeDefaults=false drops them, the
     * Anthropic client deserializer throws MissingFieldException: Fields [role, type].
     */
    @Test
    fun `serialized response includes required type and role fields`() {
        val response = MessagesResponse(
            id = "msg_resp_7",
            content = listOf(ResponseContentBlock(type = "text", text = "hi")),
            model = "m",
            stopReason = "end_turn",
            usage = Usage(inputTokens = 1, outputTokens = 2),
        )
        val json = proxyJson.encodeToString(MessagesResponse.serializer(), response)
        val obj = proxyJson.parseToJsonElement(json).jsonObject
        assertEquals("message", obj["type"]?.jsonPrimitive?.content)
        assertEquals("assistant", obj["role"]?.jsonPrimitive?.content)
        // content must be present too — it's non-null with no default.
        assertTrue(obj["content"] is kotlinx.serialization.json.JsonArray)
    }

    /**
     * Regression: request tools are serialized with proxyJson; ChatTool.type and
     * ToolCall.type default to "function". If encodeDefaults=false drops them,
     * litellm crashes with KeyError: 'type' when transforming the request.
     */
    @Test
    fun `serialized request tools include type field`() {
        val request = ChatCompletionRequest(
            model = "m",
            messages = listOf(ChatMessage(role = "user", content = JsonPrimitive("hi"))),
            tools = listOf(
                ChatTool(function = FunctionDef(name = "do_thing", parameters = buildJsonObject {})),
            ),
        )
        val json = proxyJson.encodeToString(ChatCompletionRequest.serializer(), request)
        val obj = proxyJson.parseToJsonElement(json).jsonObject
        val tools = obj["tools"]?.let { it as? kotlinx.serialization.json.JsonArray }
            ?: error("tools array missing")
        val firstTool = tools.first().jsonObject
        assertEquals("function", firstTool["type"]?.jsonPrimitive?.content)
    }
}
