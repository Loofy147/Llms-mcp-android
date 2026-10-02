package com.hicham.llmchat.runtime

import android.content.Context
import java.io.File

/** Single application composition root for the local control plane. */
object AndroidRuntimeFactory {
    @Volatile
    private var recoveryPerformedInProcess = false

    @Synchronized
    private fun recoverOncePerProcess(store: RuntimeStore) {
        if (recoveryPerformedInProcess) return
        store.recoverInterruptedEffects()
        recoveryPerformedInProcess = true
    }

    fun create(context: Context): AgentRuntime {
        val app = context.applicationContext
        val runtimeDir = File(app.filesDir, "agent-runtime")
        runtimeDir.mkdirs()
        val memoryStore = LocalMemoryStore(app)

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
                },
                "memory.note.write" to { invocation ->
                    val key = invocation.parameters["key"].orEmpty()
                    val value = invocation.parameters["value"].orEmpty()
                    val saved = memoryStore.remember(key, value)
                    CapabilityExecution(
                        output = mapOf("value" to saved),
                        observations = listOf(
                            Observation("memory_key", key.trim()),
                            Observation("memory_value", saved)
                        )
                    )
                }
            )
        )

        val runtimeStore = JournalRuntimeStore(File(runtimeDir, "runtime.journal"))
        recoverOncePerProcess(runtimeStore)

        return AgentRuntime(
            catalog = ActionCatalog(NativeActions.catalog()),
            policy = PolicyEngine(),
            capabilityExecutor = capabilityExecutor,
            store = runtimeStore,
            approvalStore = JournalApprovalStore(File(runtimeDir, "approvals.journal"))
        )
    }
}
