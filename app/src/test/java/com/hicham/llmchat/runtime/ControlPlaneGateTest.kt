package com.hicham.llmchat.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPlaneGateTest {
    private val testAttestationVerifier = EffectAttestationVerifier { true }

    private fun attestation(store: RuntimeStore, effectId: String): EffectReconciliationAttestation {
        val current = requireNotNull(store.currentEffect(effectId))
        return EffectReconciliationAttestation(
            schema = "open_system_one.effect_reconciliation_attestation.v0.1",
            effectIdentity = EffectAttestationIdentity(
                current.effectId, current.runId, current.capabilityId, current.actionId,
                current.actionVersion, current.scope, current.attributedTo, current.parametersDigest
            ),
            attemptId = current.attemptId,
            attemptNumber = current.attemptNumber,
            decision = EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED,
            decidedAt = "2026-11-12T12:01:00Z",
            authority = EffectAuthorityObservation("provider_api", "provider:test:v1", "observation:test",
                "2026-11-12T12:00:30Z", "2026-11-12T12:00:31Z"),
            evidence = EffectEvidenceReference("artifact:test", "sha256:" + "b".repeat(64)),
            verifier = EffectVerifierRecord("verifier:test", "1.0.0", "PASS", "2026-11-12T12:00:50Z"),
            accountablePrincipal = EffectAccountablePrincipal("service", "service:test"),
            authentication = EffectAuthenticationEnvelope("ed25519", "key:test",
                "sha256:" + "c".repeat(64), "signature:test"),
            reason = "Synthetic test attestation."
        )
    }

    @Test
    fun postApprovalSatisfiesApprovalRequirementAndExecutesOnce() {
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

        assertEquals(RunStatus.SUCCEEDED, resolved?.status)
        assertEquals(1, executions)
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

        assertEquals(EffectReservation.RESERVED, JournalRuntimeStore(journal, testAttestationVerifier).reserveEffects(listOf(invocation)))

        val restartedStore = JournalRuntimeStore(journal, testAttestationVerifier)
        assertEquals(1, restartedStore.recoverInterruptedEffects())
        assertEquals(EffectStatus.UNKNOWN, restartedStore.unknownEffects().single().status)
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            restartedStore.reconcileEffect(attestation(restartedStore, invocation.effectId))
        )
        assertTrue(restartedStore.unknownEffects().isEmpty())
        val retry = invocation.copy(attemptId = java.util.UUID.randomUUID().toString(), attemptNumber = 2)
        assertEquals(EffectReservation.RESERVED, restartedStore.reserveEffects(listOf(retry)))
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
