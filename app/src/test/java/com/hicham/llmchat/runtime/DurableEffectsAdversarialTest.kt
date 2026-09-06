package com.hicham.llmchat.runtime

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableEffectsAdversarialTest {
    @Test
    fun sameEffectIdDifferentParametersIsConflictNotReplayBlocked() {
        val journal = File.createTempFile("conflict", ".journal")
        journal.deleteOnExit()
        val store = JournalRuntimeStore(journal)
        val v1 = CapabilityInvocation(
            runId = "run-1",
            capabilityId = "message.send",
            actionId = "send_once",
            actionVersion = 1,
            effectId = "same-effect-id",
            attributedTo = "tester",
            parameters = mapOf("message" to "hello")
        )
        val v2 = v1.copy(parameters = mapOf("message" to "DIFFERENT PAYLOAD"))
        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(v1)))
        assertEquals(EffectReservation.CONFLICT, store.reserveEffects(listOf(v2)))
    }

    @Test
    fun tornFinalJournalRecordIsIgnoredNotFatal() {
        val journal = File.createTempFile("torn", ".journal")
        journal.deleteOnExit()
        val invocation = CapabilityInvocation(
            runId = "run-good",
            capabilityId = "cap.a",
            actionId = "action.a",
            actionVersion = 1,
            effectId = "effect-good",
            attributedTo = "tester"
        )
        val store = JournalRuntimeStore(journal)
        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(invocation)))
        RandomAccessFile(journal, "rw").use { raf ->
            raf.seek(journal.length())
            raf.write("E|dG9ybg|not-enough-fields".toByteArray())
        }
        val replayResult = runCatching {
            JournalRuntimeStore(journal).reserveEffects(listOf(invocation))
        }
        assertTrue(replayResult.isSuccess)
        assertEquals(EffectReservation.REPLAY_BLOCKED, replayResult.getOrThrow())
    }

    @Test
    fun codecRoundTripsDelimiterCharactersAndEmptyValues() {
        val journal = File.createTempFile("codec", ".journal")
        journal.deleteOnExit()
        val trickyParams = mapOf(
            "pipe|case" to "a|b~c^d,e.f:g",
            "empty" to ""
        )
        val action = ActionDefinition(
            id = "codec_test",
            version = 3,
            purpose = "codec",
            capabilities = listOf(
                CapabilityDescriptor("cap.x", EffectClass.READ_ONLY, setOf("scope|1", "scope~2"))
            ),
            execute = { ActionExecution(output = mapOf("k|1" to "v~2")) },
            plan = {
                ActionPlan(
                    listOf(
                        CapabilityInvocationSpec(
                            "cap.x",
                            setOf("scope|1", "scope~2"),
                            trickyParams
                        )
                    )
                )
            }
        )
        val catalog = ActionCatalog(listOf(action))
        val first = AgentRuntime(catalog, PolicyEngine(), JournalRuntimeStore(journal))
            .activate(ActivationRequest(ActivationSource.USER_UI, "codec_test", identity = "tester|with|pipes"))
        val restored = JournalRuntimeStore(journal).loadRun(first.id, catalog)

        assertEquals(RunStatus.SUCCEEDED, restored?.status)
        assertEquals("tester|with|pipes", restored?.activation?.identity)
        assertEquals(mapOf("k|1" to "v~2"), restored?.output)
        assertEquals(
            trickyParams,
            restored?.evidence?.capabilityInvocations?.single()?.parameters
        )
        assertEquals(
            setOf("scope|1", "scope~2"),
            restored?.evidence?.capabilityInvocations?.single()?.scope
        )
        assertFalse(restored?.evidence?.capabilityInvocations.isNullOrEmpty())
    }

    @Test
    fun planIsSoleSourceOfInvocationSet() {
        val action = ActionDefinition(
            id = "plan_is_law",
            version = 1,
            purpose = "plan authority",
            capabilities = listOf(
                CapabilityDescriptor("cap.a", EffectClass.READ_ONLY),
                CapabilityDescriptor("cap.b", EffectClass.READ_ONLY)
            ),
            execute = { ActionExecution(output = mapOf("irrelevant" to "value")) },
            plan = {
                ActionPlan(
                    listOf(
                        CapabilityInvocationSpec("cap.a"),
                        CapabilityInvocationSpec("cap.b")
                    )
                )
            }
        )
        val run = AgentRuntime(ActionCatalog(listOf(action)), PolicyEngine())
            .activate(ActivationRequest(ActivationSource.USER_UI, "plan_is_law"))
        assertEquals(
            setOf("cap.a", "cap.b"),
            run.evidence?.capabilityInvocations?.map { it.capabilityId }?.toSet()
        )
    }
}
