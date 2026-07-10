package com.jetbrains.glmproxy.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

data class ProxyConfig(
    val server: ServerConfig,
    val upstream: UpstreamConfig,
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
