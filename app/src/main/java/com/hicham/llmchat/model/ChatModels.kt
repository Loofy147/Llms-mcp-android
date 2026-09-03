package com.hicham.llmchat.model

import org.json.JSONArray
import org.json.JSONObject

/** One turn in the conversation, as sent to / received from the Messages API. */
data class ChatMessage(
    val role: String, // "user" or "assistant"
    val blocks: MutableList<ContentBlock>
)

/**
 * A single content block within a message. Mirrors the Messages API's content
 * block union — including the two MCP-connector-specific block types, which
 * only ever appear in *responses* (the API resolves those server-side).
 */
sealed class ContentBlock {
    data class Text(var text: String) : ContentBlock()
    data class ToolUse(val id: String, val name: String, var inputJson: String) : ContentBlock()
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : ContentBlock()
    data class McpToolUse(val id: String, val name: String, val serverName: String, var inputJson: String) : ContentBlock()
    data class McpToolResult(val toolUseId: String, val content: String, val isError: Boolean) : ContentBlock()
}

data class McpServerConfig(
    val name: String,
    val url: String,
    val authorizationToken: String? = null
)

/** Everything needed to build a request. Persisted via SettingsStore. */
data class AppSettings(
    val apiKey: String = "",
    val model: String = "claude-sonnet-5",
    val systemPrompt: String = "",
    val mcpServers: List<McpServerConfig> = emptyList(),
    val nativeToolsEnabled: Boolean = true
)

/** Serializes a ChatMessage back into the JSON shape the Messages API expects as input. */
fun ChatMessage.toJson(): JSONObject {
    val content = JSONArray()
    for (block in blocks) {
        val obj = JSONObject()
        when (block) {
            is ContentBlock.Text -> {
                obj.put("type", "text")
                obj.put("text", block.text)
            }
            is ContentBlock.ToolUse -> {
                obj.put("type", "tool_use")
                obj.put("id", block.id)
                obj.put("name", block.name)
                obj.put("input", if (block.inputJson.isBlank()) JSONObject() else JSONObject(block.inputJson))
            }
            is ContentBlock.ToolResult -> {
                obj.put("type", "tool_result")
                obj.put("tool_use_id", block.toolUseId)
                obj.put("content", block.content)
                obj.put("is_error", block.isError)
            }
            is ContentBlock.McpToolUse -> {
                obj.put("type", "mcp_tool_use")
                obj.put("id", block.id)
                obj.put("name", block.name)
                obj.put("server_name", block.serverName)
                obj.put("input", if (block.inputJson.isBlank()) JSONObject() else JSONObject(block.inputJson))
            }
            is ContentBlock.McpToolResult -> {
                obj.put("type", "mcp_tool_result")
                obj.put("tool_use_id", block.toolUseId)
                obj.put("is_error", block.isError)
                obj.put("content", JSONArray().put(JSONObject().put("type", "text").put("text", block.content)))
            }
        }
        content.put(obj)
    }
    return JSONObject().put("role", role).put("content", content)
}
