package com.jetbrains.glmproxy.server

import com.jetbrains.glmproxy.client.LiteLlmClient
import com.jetbrains.glmproxy.config.ProxyConfig
import com.jetbrains.glmproxy.model.anthropic.MessagesRequest
import com.jetbrains.glmproxy.translate.RequestTranslator
import com.jetbrains.glmproxy.translate.ResponseTranslator
import com.jetbrains.glmproxy.translate.StreamTranslator
import com.jetbrains.glmproxy.translate.ThinkingLogger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("Routes")

fun Application.configureRoutes(config: ProxyConfig, client: LiteLlmClient) {
    config.thinking.saveDirectory?.let {
        logger.info("Thinking blocks will be saved to {}", it)
    }

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        get("/v1/models") {
            val models = buildJsonObject {
                put("data", buildJsonArray {
                    add(buildJsonObject {
                        put("id", config.upstream.defaultModel)
                        put("display_name", "GLM 5.2 (via LiteLLM)")
                        put("created_at", "2025-01-01T00:00:00Z")
                        put("type", "model")
                    })
                })
            }
            call.respond(models)
        }

        post("/v1/messages") {
            val requestBody = call.receiveText()
            val anthropicRequest = proxyJson.decodeFromString<MessagesRequest>(requestBody)
            val openaiRequest = RequestTranslator.translate(
                anthropicRequest,
                defaultTemperature = config.upstream.temperature,
                defaultTopP = config.upstream.topP,
                forwardEffort = config.upstream.forwardEffort,
            )

            logger.info(
                "Proxying request for model={} stream={} effort={} temperature={} top_p={}",
                anthropicRequest.model,
                anthropicRequest.stream,
                openaiRequest.reasoningEffort,
                openaiRequest.temperature,
                openaiRequest.topP,
            )

            if (anthropicRequest.stream == true) {
                handleStreaming(openaiRequest, anthropicRequest.model, client, config)
            } else {
                handleNonStreaming(openaiRequest, anthropicRequest.model, client, config)
            }
        }
    }
}

private suspend fun RoutingContext.handleNonStreaming(
    openaiRequest: com.jetbrains.glmproxy.model.openai.ChatCompletionRequest,
    requestModel: String,
    client: LiteLlmClient,
    config: ProxyConfig,
) {
    val openaiResponse = client.chatCompletion(openaiRequest)
    val anthropicResponse = ResponseTranslator.translate(openaiResponse, requestModel)

    val thinkingBlock = anthropicResponse.content.firstOrNull { it.type == "thinking" }
    if (thinkingBlock != null) {
        saveThinkingBlock(config, anthropicResponse.id, requestModel, thinkingBlock.thinking ?: "")
    }

    call.respond(anthropicResponse)
}

private suspend fun RoutingContext.handleStreaming(
    openaiRequest: com.jetbrains.glmproxy.model.openai.ChatCompletionRequest,
    requestModel: String,
    client: LiteLlmClient,
    config: ProxyConfig,
) {
    val streamingRequest = openaiRequest.copy(
        stream = true,
        streamOptions = com.jetbrains.glmproxy.model.openai.StreamOptions(includeUsage = true),
    )

    val messageId = "msg_${UUID.randomUUID().toString().replace("-", "").take(24)}"
    val translator = StreamTranslator(requestModel, messageId)

    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        write("event: ping\ndata: {\"type\":\"ping\"}\n\n")
        flush()

        client.chatCompletionStream(streamingRequest) { line ->
            if (line == "[DONE]") {
                val finalEvents = translator.finish()
                for (event in finalEvents) {
                    write("event: ${event.type}\ndata: ${event.data}\n\n")
                }
            } else {
                val chunk = proxyJson.decodeFromString<com.jetbrains.glmproxy.model.openai.ChatCompletionChunk>(line)
                val events = translator.processChunk(chunk)
                for (event in events) {
                    write("event: ${event.type}\ndata: ${event.data}\n\n")
                }
            }
            flush()
        }
        // Upstream may close without [DONE] (e.g. on error); finish() is idempotent,
        // so call it again to guarantee message_delta/message_stop reach the client.
        for (event in translator.finish()) {
            write("event: ${event.type}\ndata: ${event.data}\n\n")
        }
        flush()

        // Stream done: persist the thinking blocks collected along the way.
        val blocks = translator.collectedThinkingBlocks()
        if (blocks.isNotEmpty()) {
            val saved = ThinkingLogger(config.thinking).save(messageId, blocks)
            if (saved > 0) {
                logger.info("Saved {} thinking block(s) for {}", saved, messageId)
            }
        }
    }
}

/** Persists one completed thinking block from a non-streaming response (no-op when saving is disabled). */
private fun saveThinkingBlock(config: ProxyConfig, requestId: String, model: String, text: String) {
    val block = buildJsonObject {
        put("type", "thinking")
        put("thinking", text)
        put("model", model)
        put("saved_at", java.time.Instant.now().toString())
    }
    val saved = ThinkingLogger(config.thinking).save(requestId, listOf(block))
    if (saved > 0) {
        logger.info("Saved {} thinking block(s) for {}", saved, requestId)
    }
}
