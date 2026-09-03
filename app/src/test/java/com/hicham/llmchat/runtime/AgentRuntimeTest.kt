package com.hicham.llmchat.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRuntimeTest {
    @Test
    fun deterministicActionRunsWithoutModel() {
        val runtime = AgentRuntime(ActionCatalog.demo(), PolicyEngine())

        val run = runtime.activate(
            ActivationRequest(
                source = ActivationSource.USER_UI,
                actionId = "calculate",
                input = mapOf("expression" to "12 * (3 + 4)")
            )
        )

        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals("84.0", run.output["result"])
        assertNotNull(run.evidence)
        assertEquals("calculate", run.evidence?.actionId)
        assertEquals(true, run.evidence?.verification?.passed)
    }

    @Test
    fun deniedActionDoesNotExecute() {
        val action = ActionDefinition(
            id = "danger",
            version = 1,
            purpose = "high impact test",
            capabilities = listOf(CapabilityDescriptor("danger.effect", EffectClass.HIGH_IMPACT))
        ) {
            error("must not execute")
        }
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())

        val run = runtime.activate(
            ActivationRequest(ActivationSource.USER_UI, "danger")
        )

        assertEquals(RunStatus.DENIED, run.status)
        assertNotNull(run.denialReason)
        assertNull(run.evidence)
    }

    @Test
    fun approvalIsNotEquivalentToAllow() {
        val action = ActionDefinition(
            id = "write_note",
            version = 1,
            purpose = "write a note",
            capabilities = listOf(CapabilityDescriptor("file.write", EffectClass.REVERSIBLE)),
            approvalMode = ApprovalMode.REQUIRED
        ) { error("must not execute before approval") }
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())

        val run = runtime.activate(ActivationRequest(ActivationSource.QUICK_ACTION, "write_note"))

        assertEquals(RunStatus.DENIED, run.status)
        assertEquals("Explicit approval required", run.denialReason)
    }

    @Test
    fun sameActionCanBeActivatedByDifferentSources() {
        val runtime = AgentRuntime(ActionCatalog.demo(), PolicyEngine())
        val userRun = runtime.activate(
            ActivationRequest(ActivationSource.USER_UI, "calculate", mapOf("expression" to "2 + 2"))
        )
        val automationRun = runtime.activate(
            ActivationRequest(ActivationSource.AUTOMATION, "calculate", mapOf("expression" to "2 + 2"))
        )

        assertEquals(RunStatus.SUCCEEDED, userRun.status)
        assertEquals(RunStatus.SUCCEEDED, automationRun.status)
        assertEquals(userRun.evidence?.actionId, automationRun.evidence?.actionId)
        assertEquals(ActivationSource.USER_UI, userRun.evidence?.activationSource)
        assertEquals(ActivationSource.AUTOMATION, automationRun.evidence?.activationSource)
    }

    @Test
    fun missingActionDoesNotEnterExecution() {
        val runtime = AgentRuntime(ActionCatalog.demo(), PolicyEngine())

        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "missing"))

        assertEquals(RunStatus.FAILED, run.status)
        assertNull(run.evidence)
    }
}
