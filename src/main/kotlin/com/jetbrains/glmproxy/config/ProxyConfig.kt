package com.jetbrains.glmproxy.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

data class ProxyConfig(
    val server: ServerConfig,
    val upstream: UpstreamConfig,
    val thinking: ThinkingConfig,
    val debug: DebugConfig,
) {
    companion object {
        fun load(): ProxyConfig = from(ConfigFactory.load())

        fun from(config: Config): ProxyConfig = ProxyConfig(
            server = ServerConfig(port = config.getInt("server.port")),
            upstream = UpstreamConfig(
                baseUrl = config.getString("upstream.baseUrl").trimEnd('/'),
                apiKey = config.getString("upstream.apiKey"),
                defaultModel = config.getString("upstream.defaultModel"),
                timeoutSeconds = config.getLong("upstream.timeoutSeconds"),
                insecureSkipTlsVerify = config.hasPath("upstream.insecureSkipTlsVerify") &&
                    config.getBoolean("upstream.insecureSkipTlsVerify"),
                temperature = if (config.hasPath("upstream.temperature")) config.getDouble("upstream.temperature") else null,
                topP = if (config.hasPath("upstream.topP")) config.getDouble("upstream.topP") else null,
                forwardEffort = if (config.hasPath("upstream.forwardEffort")) config.getBoolean("upstream.forwardEffort") else false,
            ),
            thinking = ThinkingConfig(
                saveDirectory = if (config.hasPath("thinking.saveDirectory")) {
                    // Blank overrides (e.g. THINKING_SAVE_DIRECTORY="") disable saving.
                    config.getString("thinking.saveDirectory").takeIf { it.isNotBlank() }
                } else null,
            ),
            debug = DebugConfig(
                logRequests = if (config.hasPath("debug.logRequests")) config.getBoolean("debug.logRequests") else true,
                logBodies = if (config.hasPath("debug.logBodies")) config.getBoolean("debug.logBodies") else false,
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
    val insecureSkipTlsVerify: Boolean = false,
    /** Sampling temperature applied when the client request omits one. */
    val temperature: Double? = null,
    /** top_p applied when the client request omits one. */
    val topP: Double? = null,
    /** Forward the client's output_config.effort to the upstream as reasoning_effort. */
    val forwardEffort: Boolean = false,
)

/** Where completed thinking blocks are persisted locally; disabled when saveDirectory is null. */
data class ThinkingConfig(val saveDirectory: String? = null)

data class DebugConfig(
    val logRequests: Boolean,
    val logBodies: Boolean,
)
