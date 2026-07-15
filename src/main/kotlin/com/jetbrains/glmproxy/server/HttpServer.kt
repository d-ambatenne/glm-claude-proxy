package com.jetbrains.glmproxy.server

import com.jetbrains.glmproxy.client.LiteLlmClient
import com.jetbrains.glmproxy.config.ProxyConfig
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val requestLogger = LoggerFactory.getLogger("Requests")

val proxyJson = Json {
    ignoreUnknownKeys = true
    // Encode fields that hold their default value. Several Anthropic/OpenAI
    // response and request fields use default values (e.g. MessagesResponse
    // type="message"/role="assistant", ChatTool/ToolCall type="function") that
    // downstream clients and upstreams treat as required. Dropping them breaks
    // deserialization on both sides, so defaults must be emitted.
    // explicitNulls = false below still omits nullable fields that are null.
    encodeDefaults = true
    explicitNulls = false
}

fun Application.configureServer(config: ProxyConfig, client: LiteLlmClient) {
    install(ContentNegotiation) {
        json(proxyJson)
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is kotlinx.coroutines.CancellationException) throw cause
            requestLogger.warn("{} {} -> 500 {}: {}",
                call.request.httpMethod.value,
                call.request.path(),
                HttpStatusCode.InternalServerError.value,
                cause.message ?: "Internal server error")
            val error = buildJsonObject {
                put("type", "error")
                put("error", buildJsonObject {
                    put("type", "api_error")
                    put("message", cause.message ?: "Internal server error")
                })
            }
            call.respond(HttpStatusCode.InternalServerError, error)
        }
    }
    if (config.debug.logRequests) {
        // Log every incoming request after it completes — including unmatched
        // paths (404) and any other endpoint — as "METHOD path -> status".
        intercept(ApplicationCallPipeline.Monitoring) {
            proceed()
            val status = call.response.status()?.value ?: 0
            requestLogger.info("{} {} -> {}",
                call.request.httpMethod.value,
                call.request.path(),
                status)
        }
    }
    configureRoutes(config, client)
}
