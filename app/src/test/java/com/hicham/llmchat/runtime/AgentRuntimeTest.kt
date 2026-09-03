package com.hicham.llmchat.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
                observations = listOf(Observation("expression", value), Observation("result", "42")),
                invocations = listOf(CapabilityInvocationSpec("calculator.evaluate", parameters = mapOf("expression" to value)))
            )
        }
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())
        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "calculate", mapOf("expression" to "40 + 2")))
        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals("42", run.output["result"])
        assertNotNull(run.evidence)
        assertEquals("calculate", run.evidence?.actionId)
        assertEquals(true, run.evidence?.verification?.passed)
        assertEquals(1, run.evidence?.capabilityInvocations?.size)
        assertEquals("calculator.evaluate", run.evidence?.capabilityInvocations?.first()?.capabilityId)
        assertEquals(run.id, run.evidence?.capabilityInvocations?.first()?.runId)
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
        ) { ActionExecution(
            output = mapOf("ok" to "true"),
            observations = listOf(Observation("ok", "true")),
            invocations = listOf(CapabilityInvocationSpec("local.ping"))
        ) }
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
        ) { ActionExecution(
            postcondition = false,
            observations = listOf(Observation("claimed", "done")),
            invocations = listOf(CapabilityInvocationSpec("test.effect"))
        ) }
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())
        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "bad_postcondition"))
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(false, run.evidence?.verification?.passed)
        assertEquals(1, run.evidence?.capabilityInvocations?.size)
    }

    @Test
    fun missingActionDoesNotEnterExecution() {
        val runtime = AgentRuntime(ActionCatalog.demo(), PolicyEngine())
        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "missing"))
        assertEquals(RunStatus.FAILED, run.status)
        assertNull(run.evidence)
    }

    @Test
    fun idempotencyKeyProducesStableEffectIdentityAcrossRuns() {
        val action = ActionDefinition(
            id = "send_once",
            version = 1,
            purpose = "stable effect identity test",
            capabilities = listOf(CapabilityDescriptor("message.send", EffectClass.REVERSIBLE))
        ) { ActionExecution(invocations = listOf(CapabilityInvocationSpec("message.send", idempotencyKey = "message-123"))) }
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())
        val first = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "send_once"))
        val retry = runtime.activate(ActivationRequest(ActivationSource.AUTOMATION, "send_once"))
        val firstEffect = first.evidence?.capabilityInvocations?.single()?.effectId
        val retryEffect = retry.evidence?.capabilityInvocations?.single()?.effectId
        assertNotNull(firstEffect)
        assertEquals(firstEffect, retryEffect)
    }

    @Test
    fun idempotencyKeysAreActionAndCapabilityScoped() {
        val actionA = ActionDefinition(
            id = "action_a",
            version = 1,
            purpose = "scope test",
            capabilities = listOf(CapabilityDescriptor("shared.effect", EffectClass.REVERSIBLE))
        ) { ActionExecution(invocations = listOf(CapabilityInvocationSpec("shared.effect", idempotencyKey = "same-key"))) }
        val actionB = actionA.copy(id = "action_b")
        val runtime = AgentRuntime(ActionCatalog(listOf(actionA, actionB)), PolicyEngine())
        val effectA = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "action_a")).evidence?.capabilityInvocations?.single()?.effectId
        val effectB = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "action_b")).evidence?.capabilityInvocations?.single()?.effectId
        assertNotEquals(effectA, effectB)
    }

    @Test
    fun undeclaredCapabilityCannotBeInvoked() {
        val action = ActionDefinition(
            id = "invalid",
            version = 1,
            purpose = "declaration enforcement",
            capabilities = listOf(CapabilityDescriptor("declared", EffectClass.READ_ONLY))
        ) { ActionExecution(invocations = listOf(CapabilityInvocationSpec("not-declared"))) }
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())
        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "invalid"))
        assertEquals(RunStatus.FAILED, run.status)
        assertNull(run.evidence)
    }

    @Test
    fun invocationCannotExceedDeclaredScope() {
        val action = ActionDefinition(
            id = "scoped",
            version = 1,
            purpose = "scope enforcement",
            capabilities = listOf(CapabilityDescriptor("file.read", EffectClass.READ_ONLY, setOf("invoices")))
        ) { ActionExecution(invocations = listOf(CapabilityInvocationSpec("file.read", setOf("private")))) }
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())
        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "scoped"))
        assertEquals(RunStatus.FAILED, run.status)
        assertNull(run.evidence)
    }
}
