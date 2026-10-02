package com.hicham.llmchat.data

import com.hicham.llmchat.model.AppSettings
import com.hicham.llmchat.model.ChatMessage
import com.hicham.llmchat.model.ContentBlock
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

/** OpenAI-compatible CSCS Inference transport for Apertus. */
class CscsClient(
    private val settings: AppSettings,
    private val model: String,
    private val runtimeToolGateway: RuntimeToolGateway,
    private val egressPolicy: EgressPolicy
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun runConversation(initialHistory: List<ChatMessage>, listener: ConversationListener) {
        val history = initialHistory
            .map { ChatMessage(it.role, it.blocks.toMutableList()) }
            .toMutableList()

        try {
            while (true) {
                val response = complete(history)

                val assistantBlocks = mutableListOf<ContentBlock>()
                response.content?.takeIf { it.isNotBlank() }?.let { assistantBlocks += ContentBlock.Text(it) }

                for (toolCall in response.toolCalls) {
                    assistantBlocks += ContentBlock.ToolUse(
                        id = toolCall.id,
                        name = toolCall.name,
                        inputJson = toolCall.arguments
                    )
                }

                if (assistantBlocks.isNotEmpty()) {
                    history += ChatMessage("assistant", assistantBlocks)
                    listener.onUpdate(history)
                }

                if (response.toolCalls.isEmpty()) {
                    listener.onComplete()
                    return
                }

                val results = mutableListOf<ContentBlock>()
                for (toolCall in response.toolCalls) {
                    val result = runtimeToolGateway.execute(toolCall.name, toolCall.arguments)
                    listener.onToolCall(toolCall.name, result)
                    results += ContentBlock.ToolResult(
                        toolUseId = toolCall.id,
                        content = result,
                        isError = result.startsWith("Error:")
                    )
                }
                history += ChatMessage("user", results)
                listener.onUpdate(history)
            }
        } catch (e: Exception) {
            listener.onError(e.message ?: "CSCS provider error")
        }
    }

    private fun complete(history: List<ChatMessage>): Completion {
        val decision = egressPolicy.decide(
            EgressRequest(
                destination = API_URL,
                purpose = "CSCS Inference API model inference",
                dataClasses = setOf(
                    EgressDataClass.USER_CONTENT,
                    EgressDataClass.USER_CONFIGURATION,
                    EgressDataClass.CREDENTIAL
                )
            )
        )
        when (decision) {
            EgressDecision.ALLOW -> Unit
            is EgressDecision.DENY -> throw IllegalStateException("Egress denied: " + decision.reason)
        }

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 2048)
            .put("temperature", 0.2)
            .put("messages", buildMessages(history))

        val tools = JSONArray()
        if (settings.nativeToolsEnabled && !model.endsWith("-thinking")) {
            for (definition in ToolRegistry.toolDefinitions()) {
                tools.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", definition.getString("name"))
                                .put("description", definition.getString("description"))
                                .put("parameters", definition.getJSONObject("input_schema"))
                        )
                )
            }
        }
        if (tools.length() > 0) body.put("tools", tools)

        val request = Request.Builder()
            .url(CHAT_COMPLETIONS_URL)
            .header("Authorization", "Bearer " + settings.apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(parseErrorMessage(raw, response.code))
            }
            return parseCompletion(raw)
        }
    }

    private fun buildMessages(history: List<ChatMessage>): JSONArray {
        val messages = JSONArray()

        for (message in history) {
            val toolUses = message.blocks.filterIsInstance<ContentBlock.ToolUse>()
            val toolResults = message.blocks.filterIsInstance<ContentBlock.ToolResult>()
            val texts = message.blocks.filterIsInstance<ContentBlock.Text>()
                .joinToString(separator = "\n") { it.text }

            if (message.role == "assistant" && toolUses.isNotEmpty()) {
                val obj = JSONObject().put("role", "assistant")
                if (texts.isNotBlank()) obj.put("content", texts)
                val calls = JSONArray()
                for (tool in toolUses) {
                    calls.put(
                        JSONObject()
                            .put("id", tool.id)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", tool.name)
                                    .put("arguments", tool.inputJson.ifBlank { "{}" })
                            )
                    )
                }
                obj.put("tool_calls", calls)
                messages.put(obj)
            } else if (message.role == "user" && toolResults.isNotEmpty()) {
                for (tool in toolResults) {
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", tool.toolUseId)
                            .put("content", tool.content)
                    )
                }
                if (texts.isNotBlank()) {
                    messages.put(JSONObject().put("role", "user").put("content", texts))
                }
            } else {
                messages.put(
                    JSONObject()
                        .put("role", message.role)
                        .put("content", texts)
                )
            }
        }

        return messages
    }

    private fun parseCompletion(raw: String): Completion {
        val root = JSONObject(raw)
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalStateException("CSCS response has no choices")
        val message = choice.optJSONObject("message")
            ?: throw IllegalStateException("CSCS response has no message")

        val content = message.optString("content").takeIf { it.isNotBlank() }
        val calls = mutableListOf<ToolCall>()
        val toolCalls = message.optJSONArray("tool_calls") ?: JSONArray()

        for (i in 0 until toolCalls.length()) {
            val call = toolCalls.getJSONObject(i)
            val function = call.getJSONObject("function")
            calls += ToolCall(
                id = call.getString("id"),
                name = function.getString("name"),
                arguments = function.optString("arguments", "{}")
            )
        }

        return Completion(content, calls)
    }

    private fun parseErrorMessage(body: String, code: Int): String = runCatching {
        JSONObject(body)
            .optJSONObject("error")
            ?.optString("message")
            ?.takeIf { it.isNotBlank() }
            ?: "HTTP " + code + ": " + body
    }.getOrDefault("HTTP " + code + ": " + body)

    private data class Completion(
        val content: String?,
        val toolCalls: List<ToolCall>
    )

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    companion object {
        const val API_URL = "https://api.inference.cscs.ch/v1"
        private const val CHAT_COMPLETIONS_URL = "$API_URL/chat/completions"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
