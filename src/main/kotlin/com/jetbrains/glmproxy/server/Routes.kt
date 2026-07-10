package com.jetbrains.glmproxy.server

import com.jetbrains.glmproxy.client.AnthropicClient
import com.jetbrains.glmproxy.client.LiteLlmClient
import com.jetbrains.glmproxy.config.ProxyConfig
import com.jetbrains.glmproxy.model.anthropic.MessagesRequest
import com.jetbrains.glmproxy.translate.RequestTranslator
import com.jetbrains.glmproxy.translate.ResponseTranslator
import com.jetbrains.glmproxy.translate.StreamTranslator
import com.jetbrains.glmproxy.translate.ToolRouter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("Routes")

fun Application.configureRoutes(
    config: ProxyConfig,
    client: LiteLlmClient,
    anthropicClient: AnthropicClient?,
) {
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

            // Requests with Anthropic server-side tools (web_search, computer, …)
            // that GLM can't satisfy are forwarded raw to the Anthropic endpoint.
            val serverSideTool = anthropicClient?.let {
                ToolRouter.matchServerSideTool(anthropicRequest, config.anthropic)
            }
            if (serverSideTool != null) {
                logger.info(
                    "Proxying request for model={} stream={} -> anthropic (tool type: {}, model: {})",
                    anthropicRequest.model, anthropicRequest.stream, serverSideTool, config.anthropic.model,
                )
                val anthropicVersion = call.request.headers["anthropic-version"]
                if (anthropicRequest.stream == true) {
                    handleAnthropicStreaming(requestBody, anthropicVersion, anthropicClient)
                } else {
                    handleAnthropicNonStreaming(requestBody, anthropicVersion, anthropicClient)
                }
            } else {
                val openaiRequest = RequestTranslator.translate(anthropicRequest)
                logger.info("Proxying request for model=${anthropicRequest.model} stream=${anthropicRequest.stream}")
                if (anthropicRequest.stream == true) {
                    handleStreaming(openaiRequest, anthropicRequest.model, client)
                } else {
                    handleNonStreaming(openaiRequest, anthropicRequest.model, client)
                }
            }
        }
    }
}

private suspend fun RoutingContext.handleAnthropicNonStreaming(
    rawRequest: String,
    anthropicVersion: String?,
    anthropicClient: AnthropicClient,
) {
    val responseText = anthropicClient.messages(rawRequest, anthropicVersion)
    call.respondText(responseText, contentType = ContentType.Application.Json)
}

private suspend fun RoutingContext.handleAnthropicStreaming(
    rawRequest: String,
    anthropicVersion: String?,
    anthropicClient: AnthropicClient,
) {
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        // Forward the upstream SSE byte stream verbatim: each line (including the
        // empty separator lines that delimit events) is written followed by '\n',
        // reconstructing the exact `event: …\ndata: …\n\n` framing the Anthropic
        // client expects.
        anthropicClient.messagesStream(rawRequest, anthropicVersion) { line ->
            write(line)
            write("\n")
            flush()
        }
        flush()
    }
}

private suspend fun RoutingContext.handleNonStreaming(
    openaiRequest: com.jetbrains.glmproxy.model.openai.ChatCompletionRequest,
    requestModel: String,
    client: LiteLlmClient,
) {
    val openaiResponse = client.chatCompletion(openaiRequest)
    val anthropicResponse = ResponseTranslator.translate(openaiResponse, requestModel)
    call.respond(anthropicResponse)
}

private suspend fun RoutingContext.handleStreaming(
    openaiRequest: com.jetbrains.glmproxy.model.openai.ChatCompletionRequest,
    requestModel: String,
    client: LiteLlmClient,
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
    }
}
