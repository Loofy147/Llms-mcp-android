package com.hicham.llmchat.runtime

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectBoundaryTest {
    private val testAttestationVerifier = EffectAttestationVerifier { true }

    private fun attestation(store: RuntimeStore, effectId: String, decision: EffectReconciliationDecision): EffectReconciliationAttestation {
        val current = requireNotNull(store.currentEffect(effectId))
        return EffectReconciliationAttestation(
            schema = "open_system_one.effect_reconciliation_attestation.v0.1",
            effectIdentity = EffectAttestationIdentity(
                current.effectId, current.runId, current.capabilityId, current.actionId,
                current.actionVersion, current.scope, current.attributedTo, current.parametersDigest
            ),
            attemptId = current.attemptId,
            attemptNumber = current.attemptNumber,
            decision = decision,
            decidedAt = "2026-11-12T12:01:00Z",
            authority = EffectAuthorityObservation("provider_api", "provider:test:v1", "observation:test",
                "2026-11-12T12:00:30Z", "2026-11-12T12:00:31Z"),
            evidence = EffectEvidenceReference("artifact:test", "sha256:" + "b".repeat(64)),
            verifier = EffectVerifierRecord("verifier:test", "1.0.0",
                if (decision == EffectReconciliationDecision.UNRESOLVED) "INCONCLUSIVE" else "PASS",
                "2026-11-12T12:00:50Z"),
            accountablePrincipal = EffectAccountablePrincipal("service", "service:test"),
            authentication = EffectAuthenticationEnvelope("ed25519", "key:test",
                "sha256:" + "c".repeat(64), "signature:test"),
            reason = "Synthetic test attestation."
        )
    }

    @Test
    fun laterEffectFailureDoesNotEraseEarlierCompletedEffect() {
        val journal = File.createTempFile("effect-boundary", ".journal")
        journal.deleteOnExit()
        val action = ActionDefinition(
            id = "two_effects",
            version = 1,
            purpose = "per-effect outcome test",
            capabilities = listOf(
                CapabilityDescriptor("effect.a", EffectClass.REVERSIBLE),
                CapabilityDescriptor("effect.b", EffectClass.REVERSIBLE)
            ),
            reduce = { _, _ -> ActionExecution() },
            plan = {
                ActionPlan(
                    listOf(
                        CapabilityInvocationSpec("effect.a", idempotencyKey = "a-1"),
                        CapabilityInvocationSpec("effect.b", idempotencyKey = "b-1")
                    )
                )
            }
        )
        val executions = mutableListOf<String>()
        val executor = RegistryCapabilityExecutor(
            mapOf(
                "effect.a" to {
                    executions += "a"
                    CapabilityExecution(observations = listOf(Observation("effect", "a-completed")))
                },
                "effect.b" to {
                    executions += "b"
                    error("effect-b transport failure")
                }
            )
        )
        val store = JournalRuntimeStore(journal, testAttestationVerifier)
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine(), executor, store)

        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "two_effects"))
        val effectA = UUID.nameUUIDFromBytes("two_effects:1:effect.a:a-1".toByteArray()).toString()
        val effectB = UUID.nameUUIDFromBytes("two_effects:1:effect.b:b-1".toByteArray()).toString()

        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(listOf("a", "b"), executions)
        assertTrue(run.evidence?.observations?.any { it.value == "a-completed" } == true)

        assertEquals(
            EffectReconciliationResult.NOT_UNKNOWN,
            store.reconcileEffect(attestation(store, effectA, EffectReconciliationDecision.CONFIRMED_COMPLETED))
        )
        assertEquals(1, store.unknownEffects().count { it.effectId == effectB })
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            store.reconcileEffect(attestation(store, effectB, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED))
        )
    }

    @Test
    fun directAllowPlanningFailureBecomesFailedRun() {
        val action = ActionDefinition(
            id = "plan_failure",
            version = 1,
            purpose = "planning failure test",
            capabilities = emptyList(),
            reduce = { _, _ -> ActionExecution() },
            plan = { error("planning exploded") }
        )
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine(), RegistryCapabilityExecutor(emptyMap()))

        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "plan_failure"))

        assertEquals(RunStatus.FAILED, run.status)
        assertEquals("planning exploded", run.denialReason)
    }

    @Test
    fun runtimeFailsClosedWhenCompletionTransitionIsRejected() {
        val delegate = InMemoryRuntimeStore(testAttestationVerifier)
        val guardedStore = object : RuntimeStore by delegate {
            override fun completeEffect(effectId: String): EffectTransitionResult =
                EffectTransitionResult.INVALID_TRANSITION
        }
        val action = ActionDefinition(
            id = "completion_rejected",
            version = 1,
            purpose = "completion persistence rejection test",
            capabilities = listOf(CapabilityDescriptor("effect.write", EffectClass.REVERSIBLE)),
            reduce = { _, _ -> ActionExecution() },
            plan = { ActionPlan(listOf(CapabilityInvocationSpec("effect.write"))) }
        )
        val runtime = AgentRuntime(
            ActionCatalog(listOf(action)),
            PolicyEngine(),
            RegistryCapabilityExecutor(
                mapOf("effect.write" to { CapabilityExecution() })
            ),
            guardedStore
        )

        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "completion_rejected"))

        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(1, delegate.unknownEffects().size)
    }

}
