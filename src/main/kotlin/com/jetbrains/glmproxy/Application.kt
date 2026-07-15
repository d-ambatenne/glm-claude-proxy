package com.jetbrains.glmproxy

import com.jetbrains.glmproxy.client.AnthropicClient
import com.jetbrains.glmproxy.client.LiteLlmClient
import com.jetbrains.glmproxy.config.ProxyConfig
import com.jetbrains.glmproxy.server.configureServer
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")

fun main() {
    val config = ProxyConfig.load()
    val client = LiteLlmClient(config.upstream, logBodies = config.debug.logBodies)

    val anthropicClient = if (config.anthropic.enabled) {
        logger.info(
            "Anthropic routing enabled: server-side tool requests ({}) -> {} /v1/messages",
            config.anthropic.toolTypes, config.anthropic.model,
        )
        AnthropicClient(config.upstream, config.anthropic, logBodies = config.debug.logBodies)
    } else null

    logger.info("Starting glm-claude-proxy on port ${config.server.port}")
    logger.info("Upstream: ${config.upstream.baseUrl} (default model: ${config.upstream.defaultModel})")
    if (config.debug.logBodies) {
        logger.info("Debug body logging enabled (>>> send upstream, <<< receive downstream)")
    }

    embeddedServer(CIO, port = config.server.port) {
        configureServer(config, client, anthropicClient)
    }.start(wait = true)
}
