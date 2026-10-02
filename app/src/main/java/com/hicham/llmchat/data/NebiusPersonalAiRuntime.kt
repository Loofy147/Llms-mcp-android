package com.hicham.llmchat.data

import android.content.Context
import com.hicham.llmchat.model.ChatMessage
import com.hicham.llmchat.runtime.AgentRuntime
import com.hicham.llmchat.runtime.AllowlistEgressPolicy
import com.hicham.llmchat.runtime.EgressDataClass
import com.hicham.llmchat.runtime.EgressPolicy

/**
 * Explicit Nebius Personal AI composition root for the hackathon vertical slice.
 *
 * This class is intentionally separate from the existing AssistantRuntime until the
 * provider contract is exercised and verified on the real device.
 */
class NebiusPersonalAiRuntime(
    context: Context,
    agentRuntime: AgentRuntime,
    model: String = DEFAULT_MODEL
) {
    private val provider = NebiusModelProvider(
        context = context.applicationContext,
        runtime = agentRuntime,
        egressPolicy = egressPolicy,
        model = model
    )

    fun runConversation(
        initialHistory: List<ChatMessage>,
        listener: ConversationListener
    ) {
        provider.runConversation(initialHistory, listener)
    }

    companion object {
        const val DEFAULT_MODEL = "nvidia/nvidia-nemotron-3-nano-30b-a3b"

        private val egressPolicy: EgressPolicy = AllowlistEgressPolicy(
            allowedHosts = setOf("api.tokenfactory.nebius.com"),
            allowedDataClasses = setOf(
                EgressDataClass.USER_CONTENT,
                EgressDataClass.USER_CONFIGURATION,
                EgressDataClass.CREDENTIAL
            )
        )
    }
}
