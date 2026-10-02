package com.hicham.llmchat.data

import android.content.Context
import com.hicham.llmchat.model.ChatMessage
import com.hicham.llmchat.runtime.AgentRuntime
import com.hicham.llmchat.runtime.EgressPolicy

/** Gemini adapter; local effects still use the canonical runtime. */
class GeminiModelProvider(
    context: Context,
    runtime: AgentRuntime,
    private val egressPolicy: EgressPolicy,
    private val model: String
) : ModelProvider {
    private val settingsStore = SettingsStore(context.applicationContext)
    private val toolGateway = RuntimeToolGateway(runtime)

    override fun runConversation(
        initialHistory: List<ChatMessage>,
        listener: ConversationListener
    ) {
        GeminiClient(
            settingsStore.load(),
            model,
            toolGateway,
            egressPolicy
        ).runConversation(initialHistory, listener)
    }
}
