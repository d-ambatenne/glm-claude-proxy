package com.jetbrains.glmproxy.translate

import com.jetbrains.glmproxy.config.AnthropicConfig
import com.jetbrains.glmproxy.model.anthropic.Message
import com.jetbrains.glmproxy.model.anthropic.MessagesRequest
import com.jetbrains.glmproxy.model.anthropic.Tool
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolRouterTest {

    private val enabled = AnthropicConfig(
        enabled = true,
        model = "anthropic/claude-opus-4-6",
        toolTypes = listOf("web_search", "computer", "text_editor", "bash"),
    )
    private val disabled = enabled.copy(enabled = false)

    private fun request(tools: List<Tool>?) = MessagesRequest(
        model = "m",
        maxTokens = 100,
        messages = listOf(Message("user", JsonPrimitive("Hi"))),
        tools = tools,
    )

    @Test
    fun `web_search tool triggers anthropic routing`() {
        val matched = ToolRouter.matchServerSideTool(
            request(listOf(Tool(name = "web_search", type = "web_search_20250305"))),
            enabled,
        )
        assertEquals("web_search_20250305", matched)
    }

    @Test
    fun `computer tool triggers anthropic routing`() {
        val matched = ToolRouter.matchServerSideTool(
            request(listOf(Tool(name = "computer", type = "computer_20250124"))),
            enabled,
        )
        assertEquals("computer_20250124", matched)
    }

    @Test
    fun `function-only tools do not trigger anthropic routing`() {
        val matched = ToolRouter.matchServerSideTool(
            request(listOf(Tool(name = "read_file", type = null))),
            enabled,
        )
        assertNull(matched)
    }

    @Test
    fun `no tools does not trigger anthropic routing`() {
        assertNull(ToolRouter.matchServerSideTool(request(null), enabled))
    }

    @Test
    fun `disabled config never routes to anthropic`() {
        val matched = ToolRouter.matchServerSideTool(
            request(listOf(Tool(name = "web_search", type = "web_search_20250305"))),
            disabled,
        )
        assertNull(matched)
    }
}
