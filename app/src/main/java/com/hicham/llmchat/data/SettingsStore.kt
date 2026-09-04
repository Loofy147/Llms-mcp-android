package com.hicham.llmchat.data

import android.content.Context
import com.hicham.llmchat.model.AppSettings
import com.hicham.llmchat.model.McpServerConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores non-secret application settings.
 * Credentials are isolated in CredentialStore, backed by Android Keystore.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val credentials = CredentialStore(context)

    fun load(): AppSettings {
        migrateLegacyCredentials()
        val servers = mutableListOf<McpServerConfig>()
        val arr = JSONArray(prefs.getString(KEY_MCP_SERVERS, "[]") ?: "[]")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.getString("name")
            servers.add(
                McpServerConfig(
                    name = name,
                    url = o.getString("url"),
                    authorizationToken = credentials.readMcpToken(name)
                )
            )
        }
        return AppSettings(
            apiKey = credentials.readApiKey().orEmpty(),
            model = prefs.getString(KEY_MODEL, "claude-sonnet-5") ?: "claude-sonnet-5",
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, "") ?: "",
            mcpServers = servers,
            nativeToolsEnabled = prefs.getBoolean(KEY_NATIVE_TOOLS, true)
        )
    }

    fun save(settings: AppSettings) {
        val previousServerNames = storedServerNames()
        val currentServerNames = settings.mcpServers.map { it.name.trim() }.filter { it.isNotBlank() }.toSet()
        (previousServerNames - currentServerNames).forEach(credentials::deleteMcpToken)

        val arr = JSONArray()
        for (server in settings.mcpServers) {
            arr.put(JSONObject().apply {
                put("name", server.name)
                put("url", server.url)
            })
            credentials.writeMcpToken(server.name, server.authorizationToken)
        }
        credentials.writeApiKey(settings.apiKey)
        prefs.edit()
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_SYSTEM_PROMPT, settings.systemPrompt)
            .putString(KEY_MCP_SERVERS, arr.toString())
            .putBoolean(KEY_NATIVE_TOOLS, settings.nativeToolsEnabled)
            .remove(KEY_API_KEY)
            .apply()
    }

    /** Deletes credential material for a removed MCP server without touching other settings. */
    fun deleteMcpServerCredential(serverName: String) {
        credentials.deleteMcpToken(serverName)
    }

    private fun storedServerNames(): Set<String> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_MCP_SERVERS, "[]") ?: "[]")
        buildSet {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name")?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }.getOrDefault(emptySet())

    private fun migrateLegacyCredentials() {
        val legacyApiKey = prefs.getString(KEY_API_KEY, null)
        if (!legacyApiKey.isNullOrBlank()) credentials.migrateLegacyApiKey(legacyApiKey)

        val legacyServers = runCatching {
            JSONArray(prefs.getString(KEY_MCP_SERVERS, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        for (i in 0 until legacyServers.length()) {
            val server = legacyServers.optJSONObject(i) ?: continue
            val name = server.optString("name").trim()
            val token = server.optString("authorization_token").ifBlank { null }
            if (name.isNotBlank() && token != null) credentials.writeMcpToken(name, token)
        }

        if (!legacyApiKey.isNullOrBlank()) prefs.edit().remove(KEY_API_KEY).apply()
        val sanitizedServers = JSONArray()
        for (i in 0 until legacyServers.length()) {
            val server = legacyServers.optJSONObject(i) ?: continue
            sanitizedServers.put(JSONObject().apply {
                put("name", server.optString("name"))
                put("url", server.optString("url"))
            })
        }
        prefs.edit().putString(KEY_MCP_SERVERS, sanitizedServers.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "llm_chat_settings"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_MCP_SERVERS = "mcp_servers"
        private const val KEY_NATIVE_TOOLS = "native_tools_enabled"
    }
}
