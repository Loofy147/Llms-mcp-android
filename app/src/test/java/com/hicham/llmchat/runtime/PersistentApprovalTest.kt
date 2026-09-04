package com.hicham.llmchat.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentApprovalTest {
    private fun action(executions: MutableList<String>) = ActionDefinition(
        id = "write_note",
        version = 1,
        purpose = "approval test",
        capabilities = listOf(CapabilityDescriptor("file.write", EffectClass.REVERSIBLE, setOf("notes"))),
        approvalMode = ApprovalMode.REQUIRED,
        reduce = { _, _ -> ActionExecution(output = mapOf("ok" to "true")) },
        plan = { input -> ActionPlan(listOf(CapabilityInvocationSpec("file.write", setOf("notes"), mapOf("text" to (input["text"] ?: "")), idempotencyKey = input["text"]))) }
    )

    @Test
    fun activationCreatesDurableBoundApproval() {
        val approvals = InMemoryApprovalStore()
        val runtime = AgentRuntime(ActionCatalog(listOf(action(mutableListOf()))), PolicyEngine(), RegistryCapabilityExecutor(emptyMap()), InMemoryRuntimeStore(), approvals)
        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "write_note", mapOf("text" to "hello"), "alice"))
        assertEquals(RunStatus.WAITING_APPROVAL, run.status)
        assertTrue(run.approvalId != null)
        val context = approvals.load(run.approvalId!!)
        assertEquals(run.id, context?.runId)
        assertEquals("alice", context?.requesterIdentity)
        assertEquals("write_note", context?.actionId)
        assertEquals(mapOf("text" to "hello"), context?.input)
        assertEquals(setOf("notes"), context?.plannedInvocations?.single()?.scope)
    }

    @Test
    fun approvalIsConsumedOnceAndExecutesSameRun() {
        val approvals = InMemoryApprovalStore()
        val executions = mutableListOf<String>()
        val capabilityExecutor = RegistryCapabilityExecutor(mapOf("file.write" to { invocation -> executions += invocation.runId; CapabilityExecution() }))
        val runtime = AgentRuntime(ActionCatalog(listOf(action(executions))), PolicyEngine(), capabilityExecutor, InMemoryRuntimeStore(), approvals)
        val pending = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "write_note", mapOf("text" to "hello"), "alice"))
        val approved = runtime.resolveApproval(pending.id, pending.approvalId!!, ApprovalDecision.APPROVED, "alice")!!
        val replay = runtime.resolveApproval(pending.id, pending.approvalId!!, ApprovalDecision.APPROVED, "alice")!!
        assertEquals(RunStatus.SUCCEEDED, approved.status)
        assertEquals(pending.id, approved.id)
        assertEquals(1, executions.size)
        assertEquals(pending.id, executions.single())
        // A terminal Run is immutable; a replay attempt must not execute again or mutate the Run.
        assertEquals(RunStatus.SUCCEEDED, replay.status)
        assertEquals(approved.id, replay.id)
    }

    @Test
    fun mismatchedInputCannotConsumeApproval() {
        val approvals = InMemoryApprovalStore()
        val runtimeStore = InMemoryRuntimeStore()
        val runtime = AgentRuntime(ActionCatalog(listOf(action(mutableListOf()))), PolicyEngine(), RegistryCapabilityExecutor(emptyMap()), runtimeStore, approvals)
        val pending = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "write_note", mapOf("text" to "hello"), "alice"))
        val context = approvals.load(pending.approvalId!!)
        assertNotEquals(null, context)
        assertEquals(ApprovalConsumption.CONFLICT, approvals.consume(pending.approvalId!!, pending.id, "alice", "different", ApprovalDecision.APPROVED, "alice"))
        assertEquals(ApprovalStatus.PENDING, approvals.load(pending.approvalId!!)?.status)
    }

    @Test
    fun durableApprovalSurvivesStoreRestartAndConsumedStatePersists() {
        val journal = File.createTempFile("approvals", ".journal")
        journal.deleteOnExit()
        val context = ApprovalContext(
            runId = "run-1",
            requesterIdentity = "alice",
            actionId = "write_note",
            actionVersion = 1,
            input = mapOf("text" to "hello"),
            plannedInvocations = listOf(CapabilityInvocationSpec("file.write", setOf("notes"), mapOf("text" to "hello"), "hello")),
            fingerprint = "fp-1"
        )
        JournalApprovalStore(journal).save(context)
        val restarted = JournalApprovalStore(journal)
        assertEquals(ApprovalStatus.PENDING, restarted.load(context.approvalId)?.status)
        assertEquals(ApprovalConsumption.CONSUMED, restarted.consume(context.approvalId, "run-1", "alice", "fp-1", ApprovalDecision.APPROVED, "alice"))
        val after = JournalApprovalStore(journal)
        assertEquals(ApprovalStatus.APPROVED, after.load(context.approvalId)?.status)
        assertEquals(ApprovalConsumption.NOT_PENDING, after.consume(context.approvalId, "run-1", "alice", "fp-1", ApprovalDecision.APPROVED, "alice"))
    }

    @Test
    fun deniedApprovalIsAlsoOneUse() {
        val store = InMemoryApprovalStore()
        val context = ApprovalContext(runId = "run-1", requesterIdentity = "alice", actionId = "a", actionVersion = 1, input = emptyMap(), plannedInvocations = emptyList(), fingerprint = "fp")
        store.save(context)
        assertEquals(ApprovalConsumption.CONSUMED, store.consume(context.approvalId, "run-1", "alice", "fp", ApprovalDecision.DENIED, "bob"))
        assertEquals(ApprovalStatus.DENIED, store.load(context.approvalId)?.status)
        assertEquals(ApprovalConsumption.NOT_PENDING, store.consume(context.approvalId, "run-1", "alice", "fp", ApprovalDecision.APPROVED, "bob"))
    }
}
