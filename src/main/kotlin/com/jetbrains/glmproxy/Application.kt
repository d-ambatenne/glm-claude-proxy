package com.jetbrains.glmproxy

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

    logger.info("Starting glm-claude-proxy on port ${config.server.port}")
    logger.info("Upstream: ${config.upstream.baseUrl} (default model: ${config.upstream.defaultModel})")
    if (config.debug.logBodies) {
        logger.info("Debug body logging enabled (>>> send upstream, <<< receive downstream)")
    }

    embeddedServer(CIO, port = config.server.port) {
        configureServer(config, client)
    }.start(wait = true)
}
