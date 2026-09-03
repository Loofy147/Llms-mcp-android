package com.hicham.llmchat.runtime

import java.io.File
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
            capabilities = listOf(CapabilityDescriptor("calculator.evaluate", EffectClass.READ_ONLY)),
            execute = { input ->
                val value = input["expression"] ?: error("Missing expression")
                ActionExecution(
                    output = mapOf("result" to "42"),
                    observations = listOf(Observation("expression", value), Observation("result", "42")),
                    invocations = listOf(CapabilityInvocationSpec("calculator.evaluate", parameters = mapOf("expression" to value)))
                )
            },
            plan = { input ->
                val value = input["expression"] ?: error("Missing expression")
                ActionPlan(listOf(CapabilityInvocationSpec("calculator.evaluate", parameters = mapOf("expression" to value))))
            }
        )
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
            capabilities = listOf(CapabilityDescriptor("local.ping", EffectClass.READ_ONLY)),
            execute = {
                ActionExecution(
                    output = mapOf("ok" to "true"),
                    observations = listOf(Observation("ok", "true")),
                    invocations = listOf(CapabilityInvocationSpec("local.ping"))
                )
            },
            plan = { ActionPlan(listOf(CapabilityInvocationSpec("local.ping"))) }
        )
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
            capabilities = listOf(CapabilityDescriptor("test.effect", EffectClass.READ_ONLY)),
            execute = {
                ActionExecution(
                    postcondition = false,
                    observations = listOf(Observation("claimed", "done")),
                    invocations = listOf(CapabilityInvocationSpec("test.effect"))
                )
            },
            plan = { ActionPlan(listOf(CapabilityInvocationSpec("test.effect"))) }
        )
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
            capabilities = listOf(CapabilityDescriptor("message.send", EffectClass.REVERSIBLE)),
            execute = { ActionExecution(invocations = listOf(CapabilityInvocationSpec("message.send", idempotencyKey = "message-123"))) },
            plan = { ActionPlan(listOf(CapabilityInvocationSpec("message.send", idempotencyKey = "message-123"))) }
        )
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())
        val first = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "send_once"))
        val retry = runtime.activate(ActivationRequest(ActivationSource.AUTOMATION, "send_once"))
        val firstEffect = first.evidence?.capabilityInvocations?.single()?.effectId
        assertNotNull(firstEffect)
        assertEquals(RunStatus.FAILED, retry.status)
        assertEquals("Effect replay blocked; reconciliation required", retry.denialReason)
    }

    @Test
    fun idempotencyKeysAreActionAndCapabilityScoped() {
        val actionA = ActionDefinition(
            id = "action_a",
            version = 1,
            purpose = "scope test",
            capabilities = listOf(CapabilityDescriptor("shared.effect", EffectClass.REVERSIBLE)),
            execute = { ActionExecution(invocations = listOf(CapabilityInvocationSpec("shared.effect", idempotencyKey = "same-key"))) },
            plan = { ActionPlan(listOf(CapabilityInvocationSpec("shared.effect", idempotencyKey = "same-key"))) }
        )
        val actionB = actionA.copy(id = "action_b")
        val runtime = AgentRuntime(ActionCatalog(listOf(actionA, actionB)), PolicyEngine())
        val effectA = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "action_a")).evidence?.capabilityInvocations?.single()?.effectId
        val effectB = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "action_b")).evidence?.capabilityInvocations?.single()?.effectId
        assertNotEquals(effectA, effectB)
    }

    @Test
    fun undeclaredCapabilityCannotBeInvoked() {
        var executed = false
        val action = ActionDefinition(
            id = "invalid",
            version = 1,
            purpose = "declaration enforcement",
            capabilities = listOf(CapabilityDescriptor("declared", EffectClass.READ_ONLY)),
            execute = {
                executed = true
                ActionExecution(invocations = listOf(CapabilityInvocationSpec("not-declared")))
            },
            plan = { ActionPlan(listOf(CapabilityInvocationSpec("not-declared"))) }
        )
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())
        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "invalid"))
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(false, executed)
        assertNull(run.evidence)
    }

    @Test
    fun invocationCannotExceedDeclaredScope() {
        var executed = false
        val action = ActionDefinition(
            id = "scoped",
            version = 1,
            purpose = "scope enforcement",
            capabilities = listOf(CapabilityDescriptor("file.read", EffectClass.READ_ONLY, setOf("invoices"))),
            execute = {
                executed = true
                ActionExecution(invocations = listOf(CapabilityInvocationSpec("file.read", setOf("private"))))
            },
            plan = { ActionPlan(listOf(CapabilityInvocationSpec("file.read", setOf("private")))) }
        )
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())
        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "scoped"))
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(false, executed)
        assertNull(run.evidence)
    }

    @Test
    fun executionCannotIntroduceUnplannedInvocation() {
        var executed = false
        val action = ActionDefinition(
            id = "unplanned",
            version = 1,
            purpose = "plan/execution consistency",
            capabilities = listOf(CapabilityDescriptor("file.write", EffectClass.REVERSIBLE)),
            execute = {
                executed = true
                ActionExecution(invocations = listOf(CapabilityInvocationSpec("file.write")))
            },
            plan = { ActionPlan() }
        )
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())
        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "unplanned"))
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(true, executed)
        assertEquals("Action execution changed its declared capability plan", run.denialReason)
    }

    @Test
    fun duplicateEffectIdentityInsideOnePlanIsRejectedBeforeExecution() {
        var executed = false
        val spec = CapabilityInvocationSpec("message.send", idempotencyKey = "same")
        val action = ActionDefinition(
            id = "duplicate",
            version = 1,
            purpose = "duplicate identity",
            capabilities = listOf(CapabilityDescriptor("message.send", EffectClass.REVERSIBLE)),
            execute = {
                executed = true
                ActionExecution(invocations = listOf(spec, spec))
            },
            plan = { ActionPlan(listOf(spec, spec)) }
        )
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())
        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "duplicate"))
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(false, executed)
        assertEquals("Action plan contains duplicate effect identities", run.denialReason)
    }

    @Test
    fun journalPersistsRunAcrossStoreInstances() {
        val journal = File.createTempFile("runtime", ".journal")
        journal.deleteOnExit()
        val action = ActionDefinition(
            id = "persisted",
            version = 1,
            purpose = "durability",
            capabilities = emptyList(),
            execute = { ActionExecution(output = mapOf("status" to "ok")) }
        )
        val catalog = ActionCatalog(listOf(action))
        val firstStore = JournalRuntimeStore(journal)
        val first = AgentRuntime(catalog, PolicyEngine(), firstStore)
            .activate(ActivationRequest(ActivationSource.USER_UI, "persisted", identity = "tester"))

        val secondStore = JournalRuntimeStore(journal)
        val restored = secondStore.loadRun(first.id, catalog)

        assertNotNull(restored)
        assertEquals(first.id, restored?.id)
        assertEquals(first.status, restored?.status)
        assertEquals(first.output, restored?.output)
        assertEquals(first.activation.identity, restored?.activation?.identity)
    }

    @Test
    fun journalDurablyBlocksEffectReplayAcrossStoreInstances() {
        val journal = File.createTempFile("effects", ".journal")
        journal.deleteOnExit()
        val invocation = CapabilityInvocation(
            runId = "run-1",
            capabilityId = "message.send",
            actionId = "send_once",
            actionVersion = 1,
            effectId = "effect-1",
            attributedTo = "tester",
            parameters = mapOf("message" to "hello")
        )
        JournalRuntimeStore(journal).reserveEffects(listOf(invocation))
        val secondStore = JournalRuntimeStore(journal)
        assertEquals(EffectReservation.REPLAY_BLOCKED, secondStore.reserveEffects(listOf(invocation)))
    }
}
