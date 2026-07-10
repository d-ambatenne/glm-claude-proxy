package com.jetbrains.glmproxy.translate

import com.jetbrains.glmproxy.model.anthropic.MessagesResponse
import com.jetbrains.glmproxy.model.anthropic.ResponseContentBlock
import com.jetbrains.glmproxy.model.anthropic.Usage
import com.jetbrains.glmproxy.model.openai.ChatCompletionResponse
import com.jetbrains.glmproxy.server.proxyJson
import kotlinx.serialization.json.JsonElement

object ResponseTranslator {

    fun translate(response: ChatCompletionResponse, requestModel: String): MessagesResponse {
        val choice = response.choices.firstOrNull()
            ?: return emptyResponse(response.id, requestModel)

        val message = choice.message
        val contentBlocks = mutableListOf<ResponseContentBlock>()

        val textContent = message.content?.let {
            proxyJson.decodeFromJsonElement(JsonElement.serializer(), it)
        }
        val text = when (textContent) {
            is kotlinx.serialization.json.JsonPrimitive -> textContent.content
            else -> null
        }

        if (!text.isNullOrEmpty()) {
            contentBlocks.add(ResponseContentBlock(type = "text", text = text))
        }

        message.toolCalls?.forEach { tc ->
            contentBlocks.add(
                ResponseContentBlock(
                    type = "tool_use",
                    id = tc.id,
                    name = tc.function.name,
                    input = proxyJson.parseToJsonElement(tc.function.arguments),
                )
            )
        }

        if (contentBlocks.isEmpty()) {
            contentBlocks.add(ResponseContentBlock(type = "text", text = ""))
        }

        return MessagesResponse(
            id = "msg_${response.id}",
            content = contentBlocks,
            model = requestModel,
            stopReason = translateStopReason(choice.finishReason),
            usage = Usage(
                inputTokens = response.usage?.promptTokens ?: 0,
                outputTokens = response.usage?.completionTokens ?: 0,
            ),
        )
    }

    private fun translateStopReason(finishReason: String?): String = when (finishReason) {
        "stop" -> "end_turn"
        "tool_calls" -> "tool_use"
        "length" -> "max_tokens"
        "content_filter" -> "end_turn"
        else -> "end_turn"
    }

    private fun emptyResponse(id: String, model: String) = MessagesResponse(
        id = "msg_$id",
        content = listOf(ResponseContentBlock(type = "text", text = "")),
        model = model,
        stopReason = "end_turn",
        usage = Usage(inputTokens = 0, outputTokens = 0),
    )
}
