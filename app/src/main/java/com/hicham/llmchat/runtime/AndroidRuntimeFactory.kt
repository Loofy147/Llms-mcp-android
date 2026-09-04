package com.hicham.llmchat.runtime

import android.content.Context
import java.io.File

/** Single application composition root for the local control plane. */
object AndroidRuntimeFactory {
    fun create(context: Context): AgentRuntime {
        val app = context.applicationContext
        val runtimeDir = File(app.filesDir, "agent-runtime")
        runtimeDir.mkdirs()

        val capabilityExecutor = RegistryCapabilityExecutor(
            mapOf(
                "device.time.read" to { NativeActions.timeNow() },
                "device.calculator.evaluate" to { invocation ->
                    val expression = invocation.parameters["expression"].orEmpty()
                    val value = SafeArithmetic.evaluate(expression)
                    CapabilityExecution(
                        output = mapOf("result" to value.toString()),
                        observations = listOf(Observation("result", value.toString()))
                    )
                }
            )
        )

        return AgentRuntime(
            catalog = ActionCatalog(NativeActions.catalog()),
            policy = PolicyEngine(),
            capabilityExecutor = capabilityExecutor,
            store = JournalRuntimeStore(File(runtimeDir, "runtime.journal")),
            approvalStore = JournalApprovalStore(File(runtimeDir, "approvals.journal"))
        )
    }
}
