package com.jetbrains.glmproxy.client

import com.jetbrains.glmproxy.config.AnthropicConfig
import com.jetbrains.glmproxy.config.UpstreamConfig
import com.jetbrains.glmproxy.server.proxyJson
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Forwards raw Anthropic Messages requests (those carrying server-side tools GLM
 * cannot satisfy, e.g. web_search) to the upstream's Anthropic-compatible
 * /v1/messages endpoint. No OpenAI translation: the body is forwarded almost
 * verbatim — only the `model` is overridden to [AnthropicConfig.model].
 *
 * Auth uses x-api-key + anthropic-version, matching the Anthropic Messages API.
 */
class AnthropicClient(
    private val upstream: UpstreamConfig,
    private val config: AnthropicConfig,
    private val logBodies: Boolean = false,
) {
    private val logger = LoggerFactory.getLogger(AnthropicClient::class.java)

    private val httpClient = HttpClient(CIO) {
        engine { requestTimeout = upstream.timeoutSeconds * 1000 }
    }

    private fun logSend(marker: String, direction: String, body: String) =
        ProxyLogger.log(logger, marker, direction, body, logBodies)

    private val endpoint: String
        get() = "${upstream.baseUrl.trimEnd('/')}/v1/messages"

    /** Non-streaming: returns the raw Anthropic response body (and its status). */
    suspend fun messages(rawRequest: String, anthropicVersion: String?): String {
        val body = overrideModel(rawRequest)
        logSend(">>>", "upstream", body)
        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            header("x-api-key", upstream.apiKey)
            header("anthropic-version", anthropicVersion ?: "2023-06-01")
            setBody(body)
        }
        val responseText = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            logSend("<<<", "downstream", "[error ${response.status.value}] $responseText")
            logger.error("Upstream Anthropic error ${response.status}: $responseText")
            throw RuntimeException("Upstream returned ${response.status}: $responseText")
        }
        logSend("<<<", "downstream", responseText)
        return responseText
    }

    /** Streaming: invokes [onSseLine] for each `data: ` payload line. */
    suspend fun messagesStream(rawRequest: String, anthropicVersion: String?, onSseLine: suspend (String) -> Unit) {
        val body = overrideModel(rawRequest)
        logSend(">>>", "upstream", body)
        httpClient.preparePost(endpoint) {
            contentType(ContentType.Application.Json)
            header("x-api-key", upstream.apiKey)
            header("anthropic-version", anthropicVersion ?: "2023-06-01")
            header("Accept", "text/event-stream")
            setBody(body)
        }.execute { response ->
            if (response.status != HttpStatusCode.OK) {
                val errorBody = response.bodyAsText()
                logSend("<<<", "downstream", "[error ${response.status.value}] $errorBody")
                logger.error("Upstream Anthropic stream error ${response.status}: $errorBody")
                throw RuntimeException("Upstream returned ${response.status}: $errorBody")
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                @Suppress("DEPRECATION")
                val line = channel.readUTF8Line() ?: break
                // Pass through every SSE line verbatim — including the empty
                // separator lines that delimit events — so framing is preserved.
                if (line.isNotEmpty()) {
                    logSend("<<<", "downstream", line)
                }
                onSseLine(line)
            }
        }
    }

    /** Rewrites the `model` field of the incoming request JSON to the configured model. */
    private fun overrideModel(rawRequest: String): String {
        val json = proxyJson.parseToJsonElement(rawRequest) as? JsonObject
            ?: return rawRequest
        val modelSet = json.toMutableMap()
        modelSet["model"] = JsonPrimitive(config.model)
        return proxyJson.encodeToString(JsonObject.serializer(), JsonObject(modelSet))
    }

    fun close() {
        httpClient.close()
    }
}
