package com.hicham.llmchat.runtime

import android.content.Context
import org.json.JSONObject

/** Small user-owned persistent memory store for bounded key/value facts. */
class LocalMemoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun remember(key: String, value: String): String {
        val normalizedKey = key.trim()
        require(normalizedKey.isNotBlank()) { "Memory key is blank" }
        require(normalizedKey.length <= MAX_KEY_LENGTH) { "Memory key is too long" }
        require(value.length <= MAX_VALUE_LENGTH) { "Memory value is too long" }

        val memories = loadObject()
        memories.put(normalizedKey, value)
        prefs.edit().putString(KEY_MEMORIES, memories.toString()).apply()
        return value
    }

    @Synchronized
    fun read(key: String): String? = loadObject().optString(key.trim(), null)

    private fun loadObject(): JSONObject = runCatching {
        JSONObject(prefs.getString(KEY_MEMORIES, "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    companion object {
        private const val PREFS_NAME = "llm_chat_memory"
        private const val KEY_MEMORIES = "facts"
        private const val MAX_KEY_LENGTH = 128
        private const val MAX_VALUE_LENGTH = 4000
    }
}
