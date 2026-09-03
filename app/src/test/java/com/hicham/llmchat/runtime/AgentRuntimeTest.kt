package com.hicham.llmchat.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRuntimeTest {
    @Test
    fun deterministicActionRunsWithoutModel() {
        val action = ActionDefinition(
            id = "calculate",
            version = 1,
            purpose = "Deterministic calculation",
            capabilities = listOf(CapabilityDescriptor("calculator.evaluate", EffectClass.READ_ONLY))
        ) { input ->
            val value = input["expression"] ?: error("Missing expression")
            ActionExecution(
                output = mapOf("result" to "42"),
                observations = listOf(Observation("expression", value), Observation("result", "42"))
            )
        }
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())

        val run = runtime.activate(
            ActivationRequest(ActivationSource.USER_UI, "calculate", mapOf("expression" to "40 + 2"))
        )

        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals("42", run.output["result"])
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
        ) { error("must not execute") }
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())

        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "danger"))

        assertEquals(RunStatus.DENIED, run.status)
        assertNotNull(run.denialReason)
        assertNull(run.evidence)
    }

    @Test
    fun approvalIsDistinctFromDenial() {
        val action = ActionDefinition(
            id = "write_note",
            version = 1,
            purpose = "write a note",
            capabilities = listOf(CapabilityDescriptor("file.write", EffectClass.REVERSIBLE)),
            approvalMode = ApprovalMode.REQUIRED
        ) { error("must not execute before approval") }
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())

        val run = runtime.activate(ActivationRequest(ActivationSource.QUICK_ACTION, "write_note"))

        assertEquals(RunStatus.WAITING_APPROVAL, run.status)
        assertEquals("Explicit approval required", run.denialReason)
    }

    @Test
    fun sameActionCanBeActivatedByDifferentSources() {
        val action = ActionDefinition(
            id = "ping",
            version = 1,
            purpose = "test activation convergence",
            capabilities = listOf(CapabilityDescriptor("local.ping", EffectClass.READ_ONLY))
        ) { ActionExecution(output = mapOf("ok" to "true"), observations = listOf(Observation("ok", "true"))) }
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())
        val userRun = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "ping"))
        val automationRun = runtime.activate(ActivationRequest(ActivationSource.AUTOMATION, "ping"))

        assertEquals(RunStatus.SUCCEEDED, userRun.status)
        assertEquals(RunStatus.SUCCEEDED, automationRun.status)
        assertEquals(userRun.evidence?.actionId, automationRun.evidence?.actionId)
        assertEquals(ActivationSource.USER_UI, userRun.evidence?.activationSource)
        assertEquals(ActivationSource.AUTOMATION, automationRun.evidence?.activationSource)
    }

    @Test
    fun failedVerificationNeverBecomesSuccess() {
        val action = ActionDefinition(
            id = "bad_postcondition",
            version = 1,
            purpose = "verification test",
            capabilities = listOf(CapabilityDescriptor("test.effect", EffectClass.READ_ONLY))
        ) { ActionExecution(postcondition = false, observations = listOf(Observation("claimed", "done"))) }
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())

        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "bad_postcondition"))

        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(false, run.evidence?.verification?.passed)
    }

    @Test
    fun missingActionDoesNotEnterExecution() {
        val runtime = AgentRuntime(ActionCatalog.demo(), PolicyEngine())

        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "missing"))

        assertEquals(RunStatus.FAILED, run.status)
        assertNull(run.evidence)
    }
}
