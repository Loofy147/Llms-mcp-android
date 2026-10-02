package com.hicham.llmchat.data

import android.content.Context
import com.hicham.llmchat.runtime.AgentRuntime
import com.hicham.llmchat.runtime.EgressPolicy
import com.hicham.llmchat.model.ChatMessage

/** CSCS Inference adapter; local effects still use the canonical runtime. */
class CscsModelProvider(
    context: Context,
    runtime: AgentRuntime,
    egressPolicy: EgressPolicy,
    private val model: String
) : ModelProvider {
    private val settingsStore = SettingsStore(context.applicationContext)
    private val toolGateway = RuntimeToolGateway(runtime)
    private val egressPolicy = egressPolicy

    override fun runConversation(initialHistory: List<ChatMessage>, listener: ConversationListener) {
        CscsClient(settingsStore.load(), model, toolGateway, egressPolicy)
            .runConversation(initialHistory, listener)
    }
}
