package com.jetbrains.glmproxy.client

import com.jetbrains.glmproxy.config.UpstreamConfig
import com.jetbrains.glmproxy.model.openai.ChatCompletionRequest
import com.jetbrains.glmproxy.model.openai.ChatCompletionResponse
import com.jetbrains.glmproxy.server.proxyJson
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import org.slf4j.LoggerFactory

class LiteLlmClient(private val config: UpstreamConfig, private val logBodies: Boolean = false) {

    private val logger = LoggerFactory.getLogger(LiteLlmClient::class.java)

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(proxyJson)
        }
        engine {
            requestTimeout = config.timeoutSeconds * 1000
        }
    }

    private fun logSend(marker: String, direction: String, body: String) =
        ProxyLogger.log(logger, marker, direction, body, logBodies)

    suspend fun chatCompletion(request: ChatCompletionRequest): ChatCompletionResponse {
        val requestJson = proxyJson.encodeToString(ChatCompletionRequest.serializer(), request)
        logSend(">>>", "upstream", requestJson)
        val response = httpClient.post("${config.baseUrl}/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${config.apiKey}")
            setBody(requestJson)
        }
        if (response.status != HttpStatusCode.OK) {
            val body = response.bodyAsText()
            logSend("<<<", "downstream", "[error ${response.status.value}] $body")
            logger.error("Upstream error ${response.status}: $body")
            throw RuntimeException("Upstream returned ${response.status}: $body")
        }
        val responseText = response.bodyAsText()
        logSend("<<<", "downstream", responseText)
        return proxyJson.decodeFromString(ChatCompletionResponse.serializer(), responseText)
    }

    suspend fun chatCompletionStream(
        request: ChatCompletionRequest,
        onLine: suspend (String) -> Unit,
    ) {
        val requestJson = proxyJson.encodeToString(ChatCompletionRequest.serializer(), request)
        logSend(">>>", "upstream", requestJson)
        httpClient.preparePost("${config.baseUrl}/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${config.apiKey}")
            header("Accept", "text/event-stream")
            setBody(requestJson)
        }.execute { response ->
            if (response.status != HttpStatusCode.OK) {
                val body = response.bodyAsText()
                logSend("<<<", "downstream", "[error ${response.status.value}] $body")
                logger.error("Upstream stream error ${response.status}: $body")
                throw RuntimeException("Upstream returned ${response.status}: $body")
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                @Suppress("DEPRECATION")
                val line = channel.readUTF8Line() ?: break
                if (line.startsWith("data: ")) {
                    val payload = line.removePrefix("data: ").trim()
                    if (payload.isNotEmpty()) {
                        logSend("<<<", "downstream", payload)
                        onLine(payload)
                    }
                }
            }
        }
    }

    fun close() {
        httpClient.close()
    }
}
