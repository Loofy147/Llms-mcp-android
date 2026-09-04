package com.hicham.llmchat.data

import android.content.Context
import com.hicham.llmchat.model.ChatMessage
import com.hicham.llmchat.runtime.AgentRuntime
import com.hicham.llmchat.runtime.EgressPolicy

/** Provider-neutral reasoning boundary used by the application facade. */
interface ModelProvider {
    fun runConversation(initialHistory: List<ChatMessage>, listener: ConversationListener)
}

/** Vendor adapter: transport and streaming only; local effects use the canonical runtime. */
class AnthropicModelProvider(
    context: Context,
    runtime: AgentRuntime,
    egressPolicy: EgressPolicy
) : ModelProvider {
    private val settingsStore = SettingsStore(context.applicationContext)
    private val toolGateway = RuntimeToolGateway(runtime)

    override fun runConversation(initialHistory: List<ChatMessage>, listener: ConversationListener) {
        AnthropicClient(settingsStore.load(), toolGateway, egressPolicy).runConversation(initialHistory, listener)
    }
}
