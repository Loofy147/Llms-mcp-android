package com.hicham.llmchat.data

import com.hicham.llmchat.model.AppSettings
import com.hicham.llmchat.model.ChatMessage
import com.hicham.llmchat.model.ContentBlock
import com.hicham.llmchat.model.toJson
import com.hicham.llmchat.runtime.EgressDataClass
import com.hicham.llmchat.runtime.EgressDecision
import com.hicham.llmchat.runtime.EgressPolicy
import com.hicham.llmchat.runtime.EgressRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface ConversationListener {
    /** Full, current message list — called after every incremental change. Safe to just re-render from this. */
    fun onUpdate(messages: List<ChatMessage>)
    fun onToolCall(name: String, result: String)
    fun onError(message: String)
    fun onComplete()
}

/**
 * Vendor transport adapter. It owns HTTP/stream parsing, but local effectful tool execution
 * is delegated to RuntimeToolGateway so the application keeps one execution authority.
 * Every remote request also crosses the local egress policy boundary.
 * MCP connector transport remains provider-owned for now.
 */
class AnthropicClient(
    private val settings: AppSettings,
    private val runtimeToolGateway: RuntimeToolGateway,
    private val egressPolicy: EgressPolicy
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun runConversation(initialHistory: List<ChatMessage>, listener: ConversationListener) {
        val history = initialHistory.map { ChatMessage(it.role, it.blocks.toMutableList()) }.toMutableList()
        try {
            while (true) {
                history.add(ChatMessage("assistant", mutableListOf()))
                listener.onUpdate(history)

                val stopReason = streamInto(history, listener)
                if (stopReason != "tool_use") {
                    listener.onComplete()
                    return
                }

                val resultBlocks = mutableListOf<ContentBlock>()
                for (block in history.last().blocks) {
                    if (block is ContentBlock.ToolUse) {
                        val result = runtimeToolGateway.execute(block.name, block.inputJson)
                        listener.onToolCall(block.name, result)
                        resultBlocks.add(ContentBlock.ToolResult(block.id, result, result.startsWith("Error:")))
                    }
                }
                if (resultBlocks.isEmpty()) {
                    listener.onComplete()
                    return
                }
                history.add(ChatMessage("user", resultBlocks))
                listener.onUpdate(history)
            }
        } catch (e: Exception) {
            listener.onError(e.message ?: "Unknown error")
        }
    }

    private fun buildRequestBody(historyExcludingInProgress: List<ChatMessage>): JSONObject {
        val body = JSONObject()
        body.put("model", settings.model)
        body.put("max_tokens", 4096)
        body.put("stream", true)
        if (settings.systemPrompt.isNotBlank()) body.put("system", settings.systemPrompt)

        val messages = JSONArray()
        for (m in historyExcludingInProgress) messages.put(m.toJson())
        body.put("messages", messages)

        val tools = JSONArray()
        if (settings.nativeToolsEnabled) {
            for (t in ToolRegistry.toolDefinitions()) tools.put(t)
        }
        for (server in settings.mcpServers) {
            tools.put(JSONObject().put("type", "mcp_toolset").put("mcp_server_name", server.name))
        }
        if (tools.length() > 0) body.put("tools", tools)

        if (settings.mcpServers.isNotEmpty()) {
            val servers = JSONArray()
            for (s in settings.mcpServers) {
                servers.put(JSONObject().apply {
                    put("type", "url")
                    put("url", s.url)
                    put("name", s.name)
                    s.authorizationToken?.let { put("authorization_token", it) }
                })
            }
            body.put("mcp_servers", servers)
        }
        return body
    }

    /** Streams one reply directly into history.last()'s blocks (mutating in place). Returns stop_reason. */
    private fun streamInto(history: MutableList<ChatMessage>, listener: ConversationListener): String? {
        val target = history.last()
        val bodyJson = buildRequestBody(history.dropLast(1))

        when (val decision = egressPolicy.decide(
            EgressRequest(
                destination = ANTHROPIC_URL,
                purpose = "Remote model inference and MCP connector request",
                dataClasses = buildSet {
                    add(EgressDataClass.USER_CONTENT)
                    add(EgressDataClass.USER_CONFIGURATION)
                    add(EgressDataClass.CREDENTIAL)
                }
            )
        )) {
            EgressDecision.ALLOW -> Unit
            is EgressDecision.DENY -> {
                listener.onError("Egress denied: ${decision.reason}")
                return null
            }
        }

        val reqBuilder = Request.Builder()
            .url(ANTHROPIC_URL)
            .header("x-api-key", settings.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
        if (settings.mcpServers.isNotEmpty()) {
            reqBuilder.header("anthropic-beta", "mcp-client-2025-11-20")
        }
        val request = reqBuilder
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var stopReason: String? = null
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                listener.onError(parseErrorMessage(response.body?.string().orEmpty(), response.code))
                return null
            }
            response.body!!.charStream().buffered().forEachLine { raw ->
                when {
                    raw.startsWith("data: ") -> {
                        val obj = runCatching { JSONObject(raw.removePrefix("data: ").trim()) }.getOrNull()
                        if (obj != null) {
                            applyEvent(obj, target, listener) { stopReason = it }
                            listener.onUpdate(history)
                        }
                    }
                }
            }
        }
        return stopReason
    }

    private fun applyEvent(
        obj: JSONObject,
        target: ChatMessage,
        listener: ConversationListener,
        setStopReason: (String) -> Unit
    ) {
        when (obj.optString("type")) {
            "content_block_start" -> {
                val index = obj.getInt("index")
                val cb = obj.getJSONObject("content_block")
                val block: ContentBlock = when (cb.optString("type")) {
                    "text" -> ContentBlock.Text(cb.optString("text", ""))
                    "tool_use" -> ContentBlock.ToolUse(cb.getString("id"), cb.getString("name"), "")
                    "mcp_tool_use" -> ContentBlock.McpToolUse(
                        cb.getString("id"), cb.getString("name"), cb.getString("server_name"), ""
                    )
                    "mcp_tool_result" -> {
                        val arr = cb.optJSONArray("content")
                        val text = if (arr != null && arr.length() > 0) arr.getJSONObject(0).optString("text", "") else ""
                        ContentBlock.McpToolResult(cb.getString("tool_use_id"), text, cb.optBoolean("is_error", false))
                    }
                    else -> ContentBlock.Text("")
                }
                while (target.blocks.size <= index) target.blocks.add(ContentBlock.Text(""))
                target.blocks[index] = block
            }
            "content_block_delta" -> {
                val index = obj.getInt("index")
                val delta = obj.getJSONObject("delta")
                when (delta.optString("type")) {
                    "text_delta" -> (target.blocks.getOrNull(index) as? ContentBlock.Text)?.let {
                        it.text += delta.getString("text")
                    }
                    "input_json_delta" -> {
                        val partial = delta.getString("partial_json")
                        when (val b = target.blocks.getOrNull(index)) {
                            is ContentBlock.ToolUse -> b.inputJson += partial
                            is ContentBlock.McpToolUse -> b.inputJson += partial
                            else -> Unit
                        }
                    }
                }
            }
            "message_delta" -> {
                obj.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotBlank() }?.let(setStopReason)
            }
            "error" -> listener.onError(obj.optJSONObject("error")?.optString("message") ?: "Streaming error")
        }
    }

    private fun parseErrorMessage(body: String, httpCode: Int): String = try {
        JSONObject(body).getJSONObject("error").getString("message")
    } catch (_: Exception) {
        "HTTP $httpCode: $body"
    }

    companion object {
        private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
    }
}
