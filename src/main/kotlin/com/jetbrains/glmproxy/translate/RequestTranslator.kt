package com.jetbrains.glmproxy.translate

import com.jetbrains.glmproxy.model.anthropic.ContentBlock
import com.jetbrains.glmproxy.model.anthropic.MessagesRequest
import com.jetbrains.glmproxy.model.openai.*
import com.jetbrains.glmproxy.server.proxyJson
import kotlinx.serialization.json.*

object RequestTranslator {

    fun translate(request: MessagesRequest): ChatCompletionRequest {
        val messages = mutableListOf<ChatMessage>()

        translateSystem(request.system)?.let { messages.add(it) }

        for (msg in request.messages) {
            messages.addAll(translateMessage(msg.role, msg.content))
        }

        return ChatCompletionRequest(
            model = request.model,
            messages = messages,
            maxTokens = request.maxTokens,
            temperature = request.temperature,
            topP = request.topP,
            stop = translateStop(request.stopSequences),
            stream = request.stream,
            tools = translateTools(request.tools),
            toolChoice = translateToolChoice(request.toolChoice),
        )
    }

    private fun translateSystem(system: JsonElement?): ChatMessage? {
        if (system == null) return null
        val text = when (system) {
            is JsonPrimitive -> system.content
            is JsonArray -> system.mapNotNull { block ->
                block.jsonObject["text"]?.jsonPrimitive?.content
            }.joinToString("\n")
            else -> return null
        }
        if (text.isBlank()) return null
        return ChatMessage(role = "system", content = JsonPrimitive(text))
    }

    private fun translateMessage(role: String, content: JsonElement): List<ChatMessage> {
        val blocks = parseContentBlocks(content)

        return when (role) {
            "user" -> translateUserMessage(blocks)
            "assistant" -> listOf(translateAssistantMessage(blocks))
            else -> listOf(ChatMessage(role = role, content = content))
        }
    }

    private fun translateUserMessage(blocks: List<ContentBlock>): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val toolResults = blocks.filter { it.type == "tool_result" }
        val otherBlocks = blocks.filter { it.type != "tool_result" }

        for (tr in toolResults) {
            messages.add(
                ChatMessage(
                    role = "tool",
                    toolCallId = tr.toolUseId,
                    content = extractToolResultContent(tr),
                )
            )
        }

        if (otherBlocks.isNotEmpty()) {
            messages.add(
                ChatMessage(
                    role = "user",
                    content = translateContentParts(otherBlocks),
                )
            )
        }

        return messages
    }

    private fun translateAssistantMessage(blocks: List<ContentBlock>): ChatMessage {
        val textParts = blocks.filter { it.type == "text" }
        val toolUses = blocks.filter { it.type == "tool_use" }

        val textContent = textParts.mapNotNull { it.text }.joinToString("")
        val toolCalls = toolUses.mapNotNull { tu ->
            val id = tu.id ?: return@mapNotNull null
            val name = tu.name ?: return@mapNotNull null
            ToolCall(
                id = id,
                function = FunctionCall(
                    name = name,
                    arguments = tu.input?.let { proxyJson.encodeToString(JsonElement.serializer(), it) } ?: "{}",
                ),
            )
        }

        return ChatMessage(
            role = "assistant",
            content = if (textContent.isNotEmpty()) JsonPrimitive(textContent) else null,
            toolCalls = toolCalls.ifEmpty { null },
        )
    }

    private fun parseContentBlocks(content: JsonElement): List<ContentBlock> = when (content) {
        is JsonPrimitive -> listOf(ContentBlock(type = "text", text = content.content))
        is JsonArray -> content.map { proxyJson.decodeFromJsonElement<ContentBlock>(it) }
        else -> emptyList()
    }

    private fun translateContentParts(blocks: List<ContentBlock>): JsonElement {
        if (blocks.size == 1 && blocks[0].type == "text") {
            return JsonPrimitive(blocks[0].text ?: "")
        }
        return buildJsonArray {
            for (block in blocks) {
                when (block.type) {
                    "text" -> add(buildJsonObject {
                        put("type", "text")
                        put("text", block.text ?: "")
                    })
                    "image" -> {
                        val source = block.source ?: continue
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:${source.mediaType};base64,${source.data}")
                            })
                        })
                    }
                }
            }
        }
    }

    private fun extractToolResultContent(tr: ContentBlock): JsonElement {
        val content = tr.content ?: return JsonPrimitive("")
        return when (content) {
            is JsonPrimitive -> content
            is JsonArray -> {
                val text = content.mapNotNull { block ->
                    val obj = block.jsonObject
                    if (obj["type"]?.jsonPrimitive?.content == "text") {
                        obj["text"]?.jsonPrimitive?.content
                    } else null
                }.joinToString("\n")
                JsonPrimitive(text)
            }
            else -> JsonPrimitive(content.toString())
        }
    }

    private fun translateStop(stopSequences: List<String>?): JsonElement? {
        if (stopSequences.isNullOrEmpty()) return null
        return if (stopSequences.size == 1) {
            JsonPrimitive(stopSequences[0])
        } else {
            buildJsonArray { stopSequences.forEach { add(it) } }
        }
    }

    private fun translateTools(tools: List<com.jetbrains.glmproxy.model.anthropic.Tool>?): List<ChatTool>? {
        if (tools.isNullOrEmpty()) return null
        return tools.map { tool ->
            ChatTool(
                function = FunctionDef(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.inputSchema,
                ),
            )
        }
    }

    private fun translateToolChoice(toolChoice: JsonElement?): JsonElement? {
        if (toolChoice == null) return null
        val obj = toolChoice as? JsonObject ?: return toolChoice
        return when (obj["type"]?.jsonPrimitive?.content) {
            "auto" -> JsonPrimitive("auto")
            "any" -> JsonPrimitive("required")
            "tool" -> buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", obj["name"]?.jsonPrimitive?.content ?: "")
                })
            }
            else -> toolChoice
        }
    }
}
