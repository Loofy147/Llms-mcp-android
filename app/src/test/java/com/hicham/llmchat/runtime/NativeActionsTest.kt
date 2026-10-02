package com.hicham.llmchat.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NativeActionsTest {
    @Test
    fun rememberDeclaresBoundedReversibleMemoryCapability() {
        val action = NativeActions.catalog().single { it.id == NativeActions.REMEMBER }

        assertEquals(EffectClass.REVERSIBLE, action.capabilities.single().effect)
        assertEquals("memory.note.write", action.capabilities.single().id)

        val plan = action.plan(
            mapOf(
                "key" to "preferred_language",
                "value" to "Arabic"
            )
        )
        val invocation = plan.invocations.single()

        assertEquals("memory.note.write", invocation.capabilityId)
        assertEquals("local.memory", invocation.scope.single())
        assertEquals("preferred_language", invocation.parameters["key"])
        assertEquals("Arabic", invocation.parameters["value"])
        assertEquals("memory:preferred_language", invocation.idempotencyKey)
    }

    @Test
    fun rememberReducesCapabilityResultAndRecordsKey() {
        val action = NativeActions.catalog().single { it.id == NativeActions.REMEMBER }

        val execution = action.reduce(
            mapOf("key" to "preferred_language", "value" to "Arabic"),
            listOf(
                CapabilityExecution(
                    output = mapOf("value" to "Arabic"),
                    observations = listOf(Observation("memory_value", "Arabic"))
                )
            )
        )

        assertNotNull(execution)
        assertEquals("Arabic", execution.output["value"])
        assertEquals("preferred_language", execution.observations.last().value)
    }
}
