package com.jetbrains.glmproxy.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

data class ProxyConfig(
    val server: ServerConfig,
    val upstream: UpstreamConfig,
    val debug: DebugConfig,
    val anthropic: AnthropicConfig,
) {
    companion object {
        fun load(): ProxyConfig = from(ConfigFactory.load())

        fun from(config: Config): ProxyConfig = ProxyConfig(
            server = ServerConfig(port = config.getInt("server.port")),
            upstream = UpstreamConfig(
                baseUrl = config.getString("upstream.baseUrl"),
                apiKey = config.getString("upstream.apiKey"),
                defaultModel = config.getString("upstream.defaultModel"),
                timeoutSeconds = config.getLong("upstream.timeoutSeconds"),
            ),
            debug = DebugConfig(
                logRequests = if (config.hasPath("debug.logRequests")) config.getBoolean("debug.logRequests") else true,
                logBodies = if (config.hasPath("debug.logBodies")) config.getBoolean("debug.logBodies") else false,
            ),
            anthropic = AnthropicConfig(
                enabled = if (config.hasPath("anthropic.enabled")) config.getBoolean("anthropic.enabled") else false,
                model = if (config.hasPath("anthropic.model")) config.getString("anthropic.model") else "anthropic/claude-opus-4-6",
                toolTypes = if (config.hasPath("anthropic.toolTypes"))
                    config.getStringList("anthropic.toolTypes") else listOf("web_search", "computer", "text_editor", "bash"),
            ),
        )
    }
}

data class ServerConfig(val port: Int)

data class UpstreamConfig(
    val baseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val timeoutSeconds: Long,
)

data class DebugConfig(
    val logRequests: Boolean,
    val logBodies: Boolean,
)

/**
 * Routes requests carrying Anthropic server-side tools (web_search, computer,
 * text_editor, bash) — which GLM/OpenAI cannot satisfy — to the upstream's
 * Anthropic-compatible /v1/messages endpoint, using a model that supports them.
 * Reuses the upstream baseUrl + apiKey. Disabled by default.
 */
data class AnthropicConfig(
    val enabled: Boolean,
    val model: String,
    val toolTypes: List<String>,
)
