package com.hicham.llmchat.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPlaneGateTest {
    @Test
    fun postApprovalApprovalRequiredNeverExecutes() {
        var executions = 0
        val action = ActionDefinition(
            id = "protected_write",
            version = 1,
            purpose = "post-approval policy gate",
            capabilities = listOf(CapabilityDescriptor("protected.write", EffectClass.REVERSIBLE)),
            approvalMode = ApprovalMode.REQUIRED,
            reduce = { _, _ -> ActionExecution() },
            plan = { ActionPlan(listOf(CapabilityInvocationSpec("protected.write", idempotencyKey = "write-1"))) }
        )
        val runtime = AgentRuntime(
            catalog = ActionCatalog(listOf(action)),
            policy = PolicyEngine(requireApprovalForReversible = true),
            capabilityExecutor = RegistryCapabilityExecutor(mapOf("protected.write" to {
                executions += 1
                CapabilityExecution()
            }))
        )

        val pending = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "protected_write", identity = "tester"))
        assertEquals(RunStatus.WAITING_APPROVAL, pending.status)
        val resolved = runtime.resolveApproval(
            runId = pending.id,
            approvalId = pending.approvalId!!,
            decision = ApprovalDecision.APPROVED,
            approverIdentity = "approver"
        )

        assertEquals(RunStatus.FAILED, resolved?.status)
        assertEquals("Policy still requires approval after approval", resolved?.denialReason)
        assertEquals(0, executions)
    }

    @Test
    fun reservedEffectBecomesUnknownAfterRestartAndIsReconciliable() {
        val journal = File.createTempFile("effects-recovery", ".journal")
        journal.deleteOnExit()
        val invocation = CapabilityInvocation(
            runId = "run-1",
            capabilityId = "message.send",
            actionId = "send_once",
            actionVersion = 1,
            effectId = "effect-recovery-1",
            attributedTo = "tester",
            parameters = mapOf("message" to "hello")
        )

        assertEquals(EffectReservation.RESERVED, JournalRuntimeStore(journal).reserveEffects(listOf(invocation)))

        val restartedStore = JournalRuntimeStore(journal)
        assertEquals(1, restartedStore.recoverInterruptedEffects())
        assertEquals(EffectStatus.UNKNOWN, restartedStore.unknownEffects().single().status)
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            restartedStore.reconcileEffect(invocation.effectId, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED)
        )
        assertTrue(restartedStore.unknownEffects().isEmpty())
        assertEquals(EffectReservation.RESERVED, restartedStore.reserveEffects(listOf(invocation)))
    }

    @Test
    fun completedEffectRemainsReplayBlocked() {
        val journal = File.createTempFile("effects-completed", ".journal")
        journal.deleteOnExit()
        val invocation = CapabilityInvocation(
            runId = "run-2",
            capabilityId = "message.send",
            actionId = "send_once",
            actionVersion = 1,
            effectId = "effect-completed-1",
            attributedTo = "tester"
        )
        val store = JournalRuntimeStore(journal)
        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(invocation)))
        store.completeEffect(invocation.effectId)
        assertEquals(EffectReservation.REPLAY_BLOCKED, JournalRuntimeStore(journal).reserveEffects(listOf(invocation)))
    }
}
