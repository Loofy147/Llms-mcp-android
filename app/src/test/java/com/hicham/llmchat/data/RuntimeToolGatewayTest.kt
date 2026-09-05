package com.hicham.llmchat.data

import com.hicham.llmchat.runtime.ActionCatalog
import com.hicham.llmchat.runtime.AgentRuntime
import com.hicham.llmchat.runtime.CapabilityExecution
import com.hicham.llmchat.runtime.EffectClass
import com.hicham.llmchat.runtime.NativeActions
import com.hicham.llmchat.runtime.PolicyEngine
import com.hicham.llmchat.runtime.RegistryCapabilityExecutor
import com.hicham.llmchat.runtime.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeToolGatewayTest {
    @Test
    fun modelToolCallConvergesOnCanonicalRuntimeAction() {
        val runtime = AgentRuntime(
            catalog = ActionCatalog(NativeActions.catalog()),
            policy = PolicyEngine(),
            capabilityExecutor = RegistryCapabilityExecutor(
                mapOf(
                    "device.calculator.evaluate" to { invocation ->
                        val expression = invocation.parameters["expression"].orEmpty()
                        CapabilityExecution(output = mapOf("result" to "42"), observations = emptyList())
                    },
                    "device.time.read" to { NativeActions.timeNow() }
                )
            )
        )
        val gateway = RuntimeToolGateway(runtime)

        val result = gateway.execute("calculate", "{\"expression\":\"40 + 2\"}")

        assertEquals("42", result)
    }

    @Test
    fun unknownToolFailsClosed() {
        val runtime = AgentRuntime(
            ActionCatalog(NativeActions.catalog()),
            PolicyEngine(),
            RegistryCapabilityExecutor(emptyMap())
        )
        val gateway = RuntimeToolGateway(runtime)

        val result = gateway.execute("not-a-tool", "{}")

        assertTrue(result.startsWith("Error:"))
    }
}
