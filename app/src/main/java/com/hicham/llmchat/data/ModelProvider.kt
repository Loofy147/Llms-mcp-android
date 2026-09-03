package com.hicham.llmchat.data

import android.content.Context
import com.hicham.llmchat.model.ChatMessage

/** Provider-neutral reasoning boundary used by the UI/runtime. */
interface ModelProvider {
    fun runConversation(initialHistory: List<ChatMessage>, listener: ConversationListener)
}

/** Current vendor adapter. The rest of the app does not depend on AnthropicClient. */
class AnthropicModelProvider(context: Context) : ModelProvider {
    private val settingsStore = SettingsStore(context.applicationContext)

    override fun runConversation(initialHistory: List<ChatMessage>, listener: ConversationListener) {
        AnthropicClient(settingsStore.load()).runConversation(initialHistory, listener)
    }
}
