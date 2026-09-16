package com.hicham.llmchat.runtime

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectBoundaryTest {
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
        val store = JournalRuntimeStore(journal)
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine(), executor, store)

        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "two_effects"))
        val effectA = UUID.nameUUIDFromBytes("two_effects:1:effect.a:a-1".toByteArray()).toString()
        val effectB = UUID.nameUUIDFromBytes("two_effects:1:effect.b:b-1".toByteArray()).toString()

        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(listOf("a", "b"), executions)
        assertTrue(run.evidence?.observations?.any { it.value == "a-completed" } == true)

        assertEquals(
            EffectReconciliationResult.NOT_UNKNOWN,
            store.reconcileEffect(effectA, EffectReconciliationDecision.CONFIRMED_COMPLETED)
        )
        assertEquals(1, store.unknownEffects().count { it.effectId == effectB })
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            store.reconcileEffect(effectB, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED)
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
}
