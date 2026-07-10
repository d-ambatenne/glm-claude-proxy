package com.jetbrains.glmproxy.server

import com.jetbrains.glmproxy.client.LiteLlmClient
import com.jetbrains.glmproxy.config.ProxyConfig
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val proxyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

fun Application.configureServer(config: ProxyConfig, client: LiteLlmClient) {
    install(ContentNegotiation) {
        json(proxyJson)
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is kotlinx.coroutines.CancellationException) throw cause
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
    configureRoutes(config, client)
}
