package com.hicham.llmchat.runtime

import java.io.File
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
        val runtime = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine(), executor, JournalRuntimeStore(journal))

        val run = runtime.activate(ActivationRequest(ActivationSource.USER_UI, "two_effects"))
        val store = JournalRuntimeStore(journal)
        val effects = listOf("a", "b").associateWith { key ->
            store.unknownEffects().find { it.parameters["__test_key"] == key }?.status
        }

        assertEquals(RunStatus.FAILED, run.status)
        assertEquals(listOf("a", "b"), executions)
        assertTrue(run.evidence?.observations?.any { it.value == "a-completed" } == true)

        // The production journal does not currently expose completed effects through a public query,
        // so inspect the journal contract through explicit effect identities used by the test plan.
        val actionId = action.id
        val invocationA = CapabilityInvocation(
            runId = run.id,
            capabilityId = "effect.a",
            actionId = actionId,
            actionVersion = 1,
            effectId = java.util.UUID.nameUUIDFromBytes("$actionId:1:effect.a:a-1".toByteArray()).toString(),
            attributedTo = "local-user"
        )
        val invocationB = CapabilityInvocation(
            runId = run.id,
            capabilityId = "effect.b",
            actionId = actionId,
            actionVersion = 1,
            effectId = java.util.UUID.nameUUIDFromBytes("$actionId:1:effect.b:b-1".toByteArray()).toString(),
            attributedTo = "local-user"
        )

        assertEquals(EffectReservation.REPLAY_BLOCKED, store.reserveEffects(listOf(invocationA)))
        assertEquals(EffectReservation.REPLAY_BLOCKED, store.reserveEffects(listOf(invocationB)))
        assertEquals(emptyMap<String, EffectStatus>(), effects.filterValues { it != null })
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
