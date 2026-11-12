package com.hicham.llmchat.runtime

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.util.Base64
import java.security.MessageDigest
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

    private fun attestation(
        effect: CapabilityInvocation,
        decision: EffectReconciliationDecision
    ) = EffectReconciliationAttestation(
        schema = "open_system_one.effect_reconciliation_attestation.v0.1",
        effectIdentity = EffectIdentityAttestation(
            schema = "open_system_one.effect_identity.v0.1",
            effectId = effect.effectId,
            runId = effect.runId,
            capabilityId = effect.capabilityId,
            actionId = effect.actionId,
            actionVersion = effect.actionVersion,
            scope = effect.scope,
            attributedTo = effect.attributedTo,
            parametersDigest = canonicalEffectParametersDigest(effect.parameters)
        ),
        attemptId = effect.attemptId,
        attemptNumber = effect.attemptNumber,
        decision = decision,
        decidedAt = "2026-11-12T12:01:00Z",
        authority = EffectReconciliationAuthority(
            kind = "synthetic_fixture",
            ref = "authority:fixture-v1",
            observationRef = "observation:${effect.effectId}",
            queriedAt = "2026-11-12T12:00:30Z",
            observedAt = "2026-11-12T12:00:31Z"
        ),
        evidence = EffectReconciliationEvidence(
            artifactRef = "artifact:synthetic:${effect.effectId}",
            digest = "sha256:" + "b".repeat(64)
        ),
        verifier = EffectReconciliationVerifierEvidence(
            ref = "verifier:fixture-v1",
            version = "1.0.0",
            result = if (decision == EffectReconciliationDecision.UNRESOLVED) "INCONCLUSIVE" else "PASS",
            verifiedAt = "2026-11-12T12:00:50Z"
        ),
        accountablePrincipal = EffectAccountablePrincipal(
            kind = "service",
            ref = "service:conformance-fixture"
        ),
        authentication = EffectAttestationAuthentication(
            scheme = "ed25519",
            keyRef = "key:synthetic-fixture",
            signedDigest = "sha256:" + "e".repeat(64),
            signature = "synthetic-fixture-signature"
        ),
        reason = "Synthetic conformance record; not provider evidence."
    )

    private val syntheticTrustVerifier = ReconciliationTrustVerifier { evidence, _ ->
        val digest = effectReconciliationAttestationDigest(evidence)
        TrustVerificationResult(
            attestationRef = "synthetic-fixture:$digest",
            attestationDigest = digest,
            trustPolicyRef = "synthetic-fixture-policy:v1",
            signatureValid = true,
            authorityApplicable = true,
            evidenceDigestVerified = true,
            verifierPolicyValid = true,
            principalAuthorized = true
        )
    }

    private fun reconcile(
        store: RuntimeStore,
        effect: CapabilityInvocation,
        decision: EffectReconciliationDecision
    ): EffectReconciliationResult = store.reconcileEffect(
        effect.effectId,
        attestation(effect, decision),
        syntheticTrustVerifier
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
            reconcile(store, effect, EffectReconciliationDecision.CONFIRMED_COMPLETED)
        )
        assertTrue(store.unknownEffects().isEmpty())

        assertEquals(
            EffectReconciliationResult.NOT_UNKNOWN,
            reconcile(store, effect, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED)
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
            reconcile(store, effect, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED)
        )

        val retry = effect.copy(attemptId = java.util.UUID.randomUUID().toString(), attemptNumber = 2)
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
            reconcile(store, effect, EffectReconciliationDecision.CONFIRMED_COMPLETED)
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
            reconcile(restarted, effect, EffectReconciliationDecision.CONFIRMED_COMPLETED)
        )

        val afterRestart = JournalRuntimeStore(journal)
        assertTrue(afterRestart.unknownEffects().isEmpty())
        assertEquals(
            EffectReconciliationResult.NOT_UNKNOWN,
            reconcile(afterRestart, effect, EffectReconciliationDecision.CONFIRMED_COMPLETED)
        )
    }

    @Test
    fun missingEffectCannotBeReconciled() {
        val store = InMemoryRuntimeStore()
        assertEquals(
            EffectReconciliationResult.NOT_FOUND,
            reconcile(store, invocation("missing"), EffectReconciliationDecision.CONFIRMED_COMPLETED)
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
            reconcile(store, original, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED)
        )

        assertEquals(
            EffectReservation.CONFLICT,
            store.reserveEffects(listOf(original))
        )
        val nextAttempt = original.copy(attemptId = java.util.UUID.randomUUID().toString(), attemptNumber = 2)
        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(nextAttempt)))
        assertTrue(JournalRuntimeStore(journal).unknownEffects().isEmpty())
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


    @Test
    fun journalUnknownEffectCannotBeCompletedWithoutReconciliation() {
        val journal = File.createTempFile("journal-unknown-no-complete", ".journal")
        journal.deleteOnExit()
        val store = JournalRuntimeStore(journal)
        val effect = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(effect)))
        assertEquals(EffectTransitionResult.APPLIED, store.markEffectUnknown(effect.effectId))
        assertEquals(
            EffectTransitionResult.INVALID_TRANSITION,
            store.completeEffect(effect.effectId)
        )
        assertEquals(EffectStatus.UNKNOWN, JournalRuntimeStore(journal).unknownEffects().single().status)
    }

    @Test
    fun journalCompletedEffectCannotBeMovedBackToUnknown() {
        val journal = File.createTempFile("journal-complete-no-unknown", ".journal")
        journal.deleteOnExit()
        val store = JournalRuntimeStore(journal)
        val effect = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(effect)))
        assertEquals(EffectTransitionResult.APPLIED, store.completeEffect(effect.effectId))
        assertEquals(
            EffectTransitionResult.INVALID_TRANSITION,
            store.markEffectUnknown(effect.effectId)
        )
        assertTrue(JournalRuntimeStore(journal).unknownEffects().isEmpty())
    }

    @Test
    fun effectReservationRejectsIncompleteImmutableIdentity() {
        val store = InMemoryRuntimeStore()
        val incomplete = invocation().copy(attributedTo = " ")

        assertEquals(
            EffectReservation.CONFLICT,
            store.reserveEffects(listOf(incomplete))
        )
    }

    @Test
    fun effectIdentityConflictsWhenParametersChangeUnderSameEffectId() {
        val store = InMemoryRuntimeStore()
        val original = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(original)))
        assertEquals(
            EffectReservation.CONFLICT,
            store.reserveEffects(listOf(original.copy(parameters = mapOf("message" to "changed"))))
        )
    }


    @Test
    fun parameterDigestUsesUnsignedUtf8KeyOrdering() {
        val store = InMemoryRuntimeStore()
        val effect = invocation().copy(
            parameters = mapOf("\uE000" to "bmp", "\uD83D\uDE00" to "astral")
        )

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(effect)))
        assertEquals(EffectTransitionResult.APPLIED, store.markEffectUnknown(effect.effectId))
        assertEquals(
            "sha256:f9e5a1137f80ad7a067063f2b8adb0aea997147ec903dc58af105e91a8de64ec",
            store.unknownEffects().single().parametersDigest
        )
    }


    private fun readKernelFixture(): Pair<ByteArray, JsonObject> {
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("kernel-conformance-fixtures.v0.3.json")
        ) { "Missing pinned kernel conformance fixture v0.3" }
        val bytes = stream.use { it.readBytes() }
        val document = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
        return bytes to document
    }

    private fun gitBlobSha(bytes: ByteArray): String {
        val header = "blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1").digest(header + bytes)
        return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun journalEffectEventCount(journal: File, effectId: String): Int {
        if (!journal.exists()) return 0
        return journal.readLines(Charsets.UTF_8).count { line ->
            val parts = line.split('|')
            if (parts.size < 2 || parts[0] !in setOf("E", "E2", "E3", "X", "A")) {
                false
            } else {
                val decoded = runCatching {
                    String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
                }.getOrElse { throw IllegalStateException("Malformed effect ID in test journal", it) }
                decoded == effectId
            }
        }
    }

    private fun invocationFromFixture(effect: JsonObject, attemptId: String, attemptNumber: Int) =
        CapabilityInvocation(
            runId = effect.get("run_id").asString,
            capabilityId = effect.get("capability_id").asString,
            actionId = effect.get("action_id").asString,
            actionVersion = effect.get("action_version").asInt,
            effectId = effect.get("effect_id").asString,
            scope = effect.getAsJsonArray("scope").map { it.asString }.toSet(),
            attributedTo = effect.get("attributed_to").asString,
            parameters = effect.getAsJsonObject("parameters").entrySet()
                .associate { it.key to it.value.asString },
            attemptId = attemptId,
            attemptNumber = attemptNumber
        )

    @Test
    fun androidJournalExecutesSharedKernelUnknownVectorV03() {
        val (fixtureBytes, fixture) = readKernelFixture()
        assertEquals(
            "7755948e1de7271d9361790af34ee604810de70e",
            gitBlobSha(fixtureBytes)
        )
        assertEquals(
            "open_system_one.kernel_conformance_fixtures.v0.3",
            fixture.get("schema").asString
        )

        val unknown = fixture.getAsJsonArray("cases")
            .map { it.asJsonObject }
            .single { it.get("id").asString == "K-UNKNOWN-01" }
        val vector = unknown.getAsJsonArray("test_vectors")[0].asJsonObject
        assertEquals(
            "open_system_one.unknown_effect_test_vector.v0.2",
            vector.get("schema").asString
        )
        val effectSpecs = vector.getAsJsonObject("effects")

        val journal = File.createTempFile("android-k-unknown-v03", ".journal")
        journal.deleteOnExit()
        val store = JournalRuntimeStore(journal)

        vector.getAsJsonArray("steps").forEachIndexed { index, element ->
            val step = element.asJsonObject
            val effectRef = step.get("effect_ref").asString
            val effectSpec = effectSpecs.getAsJsonObject(effectRef)
            val effectId = effectSpec.get("effect_id").asString
            val attemptId = step.get("attempt_id")?.asString
                ?: effectSpec.get("attempt_id").asString
            val attemptNumber = step.get("attempt_number")?.asInt
                ?: effectSpec.get("attempt_number").asInt
            val invocation = invocationFromFixture(effectSpec, attemptId, attemptNumber)
            val beforeCount = journalEffectEventCount(journal, effectId)

            val operation = step.get("op").asString
            val actualResult = when (operation) {
                "reserve" -> store.reserveEffects(listOf(invocation)).name
                "mark_unknown" -> {
                    val transition = store.markEffectUnknown(effectId)
                    if (transition == EffectTransitionResult.APPLIED) "UNKNOWN" else transition.name
                }
                "reconcile" -> {
                    val decision = EffectReconciliationDecision.valueOf(step.get("decision").asString)
                    reconcile(store, invocation, decision).name
                }
                else -> throw AssertionError("Unsupported K-UNKNOWN operation: $operation")
            }

            val current = store.currentEffect(effectId)
            val actualStatus = current?.status?.name
            val afterCount = journalEffectEventCount(journal, effectId)
            val expected = step.getAsJsonObject("expected")
            val expectedResult = expected.get("result").asString
            val expectedStatus = expected.get("status").asString
            val expectedDelta = expected.get("record_count_delta").asInt

            assertEquals("step ${index + 1} result", expectedResult, actualResult)
            assertEquals("step ${index + 1} status", expectedStatus, actualStatus)
            assertEquals("step ${index + 1} record delta", expectedDelta, afterCount - beforeCount)

            if (operation == "reserve" && expectedResult == "RESERVED") {
                assertEquals("step ${index + 1} attempt ID", attemptId, current?.attemptId)
                assertEquals("step ${index + 1} attempt number", attemptNumber, current?.attemptNumber)
            }
        }
    }

    @Test
    fun journalRejectsAttemptNumberRegressionAndAttemptIdReuse() {
        val journal = File.createTempFile("attempt-number-replay", ".journal")
        journal.deleteOnExit()
        val store = JournalRuntimeStore(journal)
        val original = invocation()

        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(original)))
        assertEquals(EffectTransitionResult.APPLIED, store.markEffectUnknown(original.effectId))
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            reconcile(store, original, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED)
        )

        assertEquals(
            EffectReservation.CONFLICT,
            store.reserveEffects(listOf(original.copy(
                attemptId = java.util.UUID.randomUUID().toString(),
                attemptNumber = 1
            )))
        )
        val attemptTwo = original.copy(
            attemptId = java.util.UUID.randomUUID().toString(),
            attemptNumber = 2
        )
        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(attemptTwo)))
        assertEquals(EffectTransitionResult.APPLIED, store.markEffectUnknown(original.effectId))
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            reconcile(store, attemptTwo, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED)
        )
        assertEquals(
            EffectReservation.CONFLICT,
            store.reserveEffects(listOf(attemptTwo.copy(
                attemptNumber = 3
            )))
        )
        val attemptThree = attemptTwo.copy(
            attemptId = java.util.UUID.randomUUID().toString(),
            attemptNumber = 3
        )
        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(attemptThree)))
        assertEquals(3, JournalRuntimeStore(journal).currentEffect(original.effectId)?.attemptNumber)
    }

}
