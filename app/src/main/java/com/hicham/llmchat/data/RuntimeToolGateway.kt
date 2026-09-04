package com.hicham.llmchat.data

import com.hicham.llmchat.runtime.ActivationRequest
import com.hicham.llmchat.runtime.ActivationSource
import com.hicham.llmchat.runtime.AgentRuntime
import com.hicham.llmchat.runtime.NativeActions
import com.hicham.llmchat.runtime.RunStatus
import org.json.JSONObject

/**
 * Provider-facing adapter that translates model tool calls into canonical runtime Activations.
 * It contains no effect implementation of its own.
 */
class RuntimeToolGateway(private val runtime: AgentRuntime) {
    fun execute(name: String, inputJson: String, identity: String = "local-user"): String {
        val actionId = when (name) {
            "get_current_time" -> NativeActions.CURRENT_TIME
            "calculate" -> NativeActions.CALCULATE
            else -> return "Error: unknown tool '$name'"
        }
        val input = parseStringMap(inputJson)
        val run = runtime.activate(
            ActivationRequest(
                source = ActivationSource.MODEL,
                actionId = actionId,
                input = input,
                identity = identity
            )
        )
        return when (run.status) {
            RunStatus.SUCCEEDED -> run.output["value"] ?: run.output["result"] ?: ""
            RunStatus.DENIED,
            RunStatus.WAITING_APPROVAL,
            RunStatus.FAILED,
            RunStatus.CANCELLED -> "Error: ${run.denialReason ?: run.status.name}"
            RunStatus.CREATED,
            RunStatus.RUNNING -> "Error: runtime did not reach a terminal state"
        }
    }

    private fun parseStringMap(inputJson: String): Map<String, String> {
        if (inputJson.isBlank()) return emptyMap()
        val json = JSONObject(inputJson)
        val result = linkedMapOf<String, String>()
        for (key in json.keys()) {
            result[key] = json.opt(key)?.toString().orEmpty()
        }
        return result
    }
}
