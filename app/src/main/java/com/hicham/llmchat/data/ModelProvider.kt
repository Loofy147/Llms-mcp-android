package com.hicham.llmchat.data

import android.content.Context
import com.hicham.llmchat.model.ChatMessage
import com.hicham.llmchat.runtime.AndroidRuntimeFactory

/** Provider-neutral reasoning boundary used by the UI/runtime. */
interface ModelProvider {
    fun runConversation(initialHistory: List<ChatMessage>, listener: ConversationListener)
}

/**
 * Current vendor adapter. Anthropic remains a transport/provider boundary;
 * local effects are delegated to the application's canonical runtime.
 */
class AnthropicModelProvider(context: Context) : ModelProvider {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val runtime = AndroidRuntimeFactory.create(appContext)
    private val toolGateway = RuntimeToolGateway(runtime)

    override fun runConversation(initialHistory: List<ChatMessage>, listener: ConversationListener) {
        AnthropicClient(settingsStore.load(), toolGateway).runConversation(initialHistory, listener)
    }
}
