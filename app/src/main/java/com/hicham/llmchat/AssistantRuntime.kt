package com.hicham.llmchat

import android.content.Context
import com.hicham.llmchat.data.AnthropicModelProvider
import com.hicham.llmchat.data.ConversationListener
import com.hicham.llmchat.model.ChatMessage
import com.hicham.llmchat.runtime.ActivationRequest
import com.hicham.llmchat.runtime.AgentRuntime
import com.hicham.llmchat.runtime.AllowlistEgressPolicy
import com.hicham.llmchat.runtime.AndroidRuntimeFactory
import com.hicham.llmchat.runtime.EgressDataClass
import com.hicham.llmchat.runtime.EgressPolicy
import com.hicham.llmchat.runtime.Run

/**
 * Application-level facade that keeps UI activation surfaces converged on one control plane.
 * It exposes reasoning and deterministic Action execution without making either a second authority.
 */
class AssistantRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val agentRuntime: AgentRuntime = AndroidRuntimeFactory.create(appContext)
    private val egressPolicy: EgressPolicy = AllowlistEgressPolicy(
        allowedHosts = setOf("api.anthropic.com"),
        allowedDataClasses = setOf(
            EgressDataClass.USER_CONTENT,
            EgressDataClass.USER_CONFIGURATION,
            EgressDataClass.CREDENTIAL
        )
    )
    private val modelProvider = AnthropicModelProvider(appContext, agentRuntime, egressPolicy)

    fun runConversation(initialHistory: List<ChatMessage>, listener: ConversationListener) {
        modelProvider.runConversation(initialHistory, listener)
    }

    fun activate(request: ActivationRequest): Run = agentRuntime.activate(request)
}
