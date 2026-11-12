package com.hicham.llmchat.runtime

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownEffectReconciliationTest {
    private fun invocation(effectId: String = "effect-1") = CapabilityInvocation(
        runId = "run-1",
        capabilityId = "message.send",
        actionId = "send_once",
        actionVersion = 1,
        effectId = effectId,
        attributedTo = "tester",
        parameters = mapOf("message" to "hello")
    )

    @Test
    fun unknownEffectIsEnumeratedAndCanBeConfirmedCompleted() {
        val store = InMemoryRuntimeStore()
        val effect = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(effect)))
        assertEquals(EffectTransitionResult.APPLIED, store.markEffectUnknown(effect.effectId))

        assertEquals(1, store.unknownEffects().size)
        assertEquals(EffectStatus.UNKNOWN, store.unknownEffects().single().status)

        assertEquals(
            EffectReconciliationResult.RECONCILED,
            store.reconcileEffect(effect.effectId, EffectReconciliationDecision.CONFIRMED_COMPLETED)
        )
        assertTrue(store.unknownEffects().isEmpty())

        assertEquals(
            EffectReconciliationResult.NOT_UNKNOWN,
            store.reconcileEffect(effect.effectId, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED)
        )
    }

    @Test
    fun confirmedNotExecutedAllowsControlledRetry() {
        val store = InMemoryRuntimeStore()
        val effect = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(effect)))
        assertEquals(EffectTransitionResult.APPLIED, store.markEffectUnknown(effect.effectId))
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            store.reconcileEffect(effect.effectId, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED)
        )

        val retry = effect.copy(attemptId = java.util.UUID.randomUUID().toString())
        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(retry)))
        assertTrue(store.unknownEffects().isEmpty())
    }

    @Test
    fun completedEffectCannotBeReconciled() {
        val store = InMemoryRuntimeStore()
        val effect = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(effect)))
        assertEquals(EffectTransitionResult.APPLIED, store.completeEffect(effect.effectId))

        assertEquals(
            EffectReconciliationResult.NOT_UNKNOWN,
            store.reconcileEffect(effect.effectId, EffectReconciliationDecision.CONFIRMED_COMPLETED)
        )
    }

    @Test
    fun unknownEffectAndReconciliationSurviveJournalRestart() {
        val journal = File.createTempFile("unknown-effects", ".journal")
        journal.deleteOnExit()
        val effect = invocation()

        val first = JournalRuntimeStore(journal)
        assertEquals(EffectReservation.RESERVED, first.reserveEffects(listOf(effect)))
        first.markEffectUnknown(effect.effectId)

        val restarted = JournalRuntimeStore(journal)
        assertEquals(effect.effectId, restarted.unknownEffects().single().effectId)
        assertEquals(EffectStatus.UNKNOWN, restarted.unknownEffects().single().status)

        assertEquals(
            EffectReconciliationResult.RECONCILED,
            restarted.reconcileEffect(effect.effectId, EffectReconciliationDecision.CONFIRMED_COMPLETED)
        )

        val afterRestart = JournalRuntimeStore(journal)
        assertTrue(afterRestart.unknownEffects().isEmpty())
        assertEquals(
            EffectReconciliationResult.NOT_UNKNOWN,
            afterRestart.reconcileEffect(effect.effectId, EffectReconciliationDecision.CONFIRMED_COMPLETED)
        )
    }

    @Test
    fun missingEffectCannotBeReconciled() {
        val store = InMemoryRuntimeStore()
        assertEquals(
            EffectReconciliationResult.NOT_FOUND,
            store.reconcileEffect("missing", EffectReconciliationDecision.CONFIRMED_COMPLETED)
        )
    }

    @Test
    fun unknownEffectCannotBeCompletedWithoutReconciliation() {
        val store = InMemoryRuntimeStore()
        val effect = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(effect)))
        store.markEffectUnknown(effect.effectId)
        assertEquals(
            EffectTransitionResult.INVALID_TRANSITION,
            store.completeEffect(effect.effectId)
        )

        assertEquals(EffectStatus.UNKNOWN, store.unknownEffects().single().status)
    }

    @Test
    fun completedEffectCannotBeMovedBackToUnknown() {
        val store = InMemoryRuntimeStore()
        val effect = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(effect)))
        assertEquals(EffectTransitionResult.APPLIED, store.completeEffect(effect.effectId))
        assertEquals(
            EffectTransitionResult.INVALID_TRANSITION,
            store.markEffectUnknown(effect.effectId)
        )

        assertTrue(store.unknownEffects().isEmpty())
    }

    @Test
    fun journalRejectsRunIdentityMismatchForAnExistingEffectId() {
        val journal = File.createTempFile("effect-run-identity", ".journal")
        journal.deleteOnExit()
        val store = JournalRuntimeStore(journal)
        val original = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(original)))
        val alteredRun = original.copy(runId = "different-run")

        assertEquals(
            EffectReservation.CONFLICT,
            store.reserveEffects(listOf(alteredRun))
        )
    }

    @Test
    fun journalFailsClosedOnMalformedTrailingRecord() {
        val journal = File.createTempFile("effect-corrupt-tail", ".journal")
        journal.deleteOnExit()
        val store = JournalRuntimeStore(journal)
        val effect = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(effect)))
        journal.appendText("X|not-valid-base64|UNKNOWN\\n")

        val restarted = JournalRuntimeStore(journal)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            restarted.unknownEffects()
        }
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            restarted.reserveEffects(listOf(invocation("another-effect")))
        }
    }

    @Test
    fun journalRejectsIllegalStatusTransitionDuringReplay() {
        val journal = File.createTempFile("effect-invalid-transition", ".journal")
        journal.deleteOnExit()
        val store = JournalRuntimeStore(journal)
        val effect = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(effect)))
        assertEquals(EffectTransitionResult.APPLIED, store.completeEffect(effect.effectId))
        val encodedId = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(effect.effectId.toByteArray(Charsets.UTF_8))
        journal.appendText("X|$encodedId|UNKNOWN\\n")
        val restarted = JournalRuntimeStore(journal)

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            restarted.unknownEffects()
        }
    }


    @Test
    fun confirmedNotExecutedRequiresANewAttemptId() {
        val journal = File.createTempFile("effect-attempt-identity", ".journal")
        journal.deleteOnExit()
        val store = JournalRuntimeStore(journal)
        val original = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(original)))
        assertEquals(EffectTransitionResult.APPLIED, store.markEffectUnknown(original.effectId))
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            store.reconcileEffect(original.effectId, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED)
        )

        assertEquals(
            EffectReservation.CONFLICT,
            store.reserveEffects(listOf(original))
        )
        val nextAttempt = original.copy(attemptId = java.util.UUID.randomUUID().toString())
        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(nextAttempt)))
        assertEquals(nextAttempt.attemptId, JournalRuntimeStore(journal).loadEffectForTest(original.effectId)?.attemptId)
    }

    @Test
    fun journalRejectsCorruptionInTheMiddleOfHistory() {
        val journal = File.createTempFile("effect-corrupt-middle", ".journal")
        journal.deleteOnExit()
        val store = JournalRuntimeStore(journal)
        val effect = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(effect)))
        val encodedId = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(effect.effectId.toByteArray(Charsets.UTF_8))
        journal.appendText("NOT_A_RECORD\\nX|$encodedId|UNKNOWN\\n")

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            JournalRuntimeStore(journal).reserveEffects(listOf(invocation("other")))
        }
    }

    @Test
    fun writeFullyHandlesShortChannelWrites() {
        val output = ByteArrayOutputStream()
        val channel = object : WritableByteChannel {
            private var open = true
            override fun isOpen(): Boolean = open
            override fun close() { open = false }
            override fun write(source: ByteBuffer): Int {
                if (!open) throw java.nio.channels.ClosedChannelException()
                val count = minOf(2, source.remaining())
                repeat(count) { output.write(source.get().toInt()) }
                return count
            }
        }
        val payload = "complete-journal-record\\n".toByteArray(Charsets.UTF_8)

        writeFully(channel, ByteBuffer.wrap(payload))

        org.junit.Assert.assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun missingEffectTransitionsReturnNotFound() {
        val store = InMemoryRuntimeStore()
        assertEquals(EffectTransitionResult.NOT_FOUND, store.completeEffect("missing"))
        assertEquals(EffectTransitionResult.NOT_FOUND, store.markEffectUnknown("missing"))
    }

}
