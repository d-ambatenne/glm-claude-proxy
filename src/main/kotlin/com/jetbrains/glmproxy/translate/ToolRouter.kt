package com.jetbrains.glmproxy.translate

import com.jetbrains.glmproxy.config.AnthropicConfig
import com.jetbrains.glmproxy.model.anthropic.MessagesRequest

/**
 * Decides whether a request should bypass the OpenAI/GLM translation and be
 * forwarded raw to the Anthropic endpoint instead. This is the case when the
 * request uses Anthropic server-side tools (web_search, computer, text_editor,
 * bash) that GLM/LiteLLM does not implement.
 */
object ToolRouter {

    /**
     * Returns the matching server-side tool type if the request should be routed
     * to the Anthropic endpoint, or null if it should go through the normal GLM path.
     * Matching is by prefix so dated variants (web_search_20250305, computer_20250124)
     * are covered automatically.
     */
    fun matchServerSideTool(request: MessagesRequest, config: AnthropicConfig): String? {
        if (!config.enabled) return null
        val tools = request.tools ?: return null
        for (tool in tools) {
            val type = tool.type ?: continue
            for (prefix in config.toolTypes) {
                if (type == prefix || type.startsWith("${prefix}_")) return type
            }
        }
        return null
    }
}
