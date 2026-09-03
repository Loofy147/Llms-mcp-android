package com.hicham.llmchat.data

import android.content.Context
import com.hicham.llmchat.model.AppSettings
import com.hicham.llmchat.model.McpServerConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin wrapper over SharedPreferences. No Room/DataStore dependency, since
 * the data here is small and simple.
 *
 * SECURITY NOTE: this stores the API key in plain SharedPreferences, not
 * EncryptedSharedPreferences (androidx.security.crypto). That's fine on a
 * single-user device with no root/backup exposure, but it's a real
 * simplification, not an oversight — see README "Known simplifications".
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("llm_chat_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val servers = mutableListOf<McpServerConfig>()
        val arr = JSONArray(prefs.getString(KEY_MCP_SERVERS, "[]") ?: "[]")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            servers.add(
                McpServerConfig(
                    name = o.getString("name"),
                    url = o.getString("url"),
                    authorizationToken = o.optString("authorization_token", "").ifBlank { null }
                )
            )
        }
        return AppSettings(
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            model = prefs.getString(KEY_MODEL, "claude-sonnet-5") ?: "claude-sonnet-5",
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, "") ?: "",
            mcpServers = servers,
            nativeToolsEnabled = prefs.getBoolean(KEY_NATIVE_TOOLS, true)
        )
    }

    fun save(settings: AppSettings) {
        val arr = JSONArray()
        for (s in settings.mcpServers) {
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("url", s.url)
                s.authorizationToken?.let { put("authorization_token", it) }
            })
        }
        prefs.edit()
            .putString(KEY_API_KEY, settings.apiKey)
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_SYSTEM_PROMPT, settings.systemPrompt)
            .putString(KEY_MCP_SERVERS, arr.toString())
            .putBoolean(KEY_NATIVE_TOOLS, settings.nativeToolsEnabled)
            .apply()
    }

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_MCP_SERVERS = "mcp_servers"
        private const val KEY_NATIVE_TOOLS = "native_tools_enabled"
    }
}
