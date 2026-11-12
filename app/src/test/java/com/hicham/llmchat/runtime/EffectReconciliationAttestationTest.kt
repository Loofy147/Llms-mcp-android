package com.hicham.llmchat.runtime

import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectReconciliationAttestationTest {
    private fun invocation(effectId: String = "effect-attestation") = CapabilityInvocation(
        runId = "run-attestation",
        capabilityId = "message.send",
        actionId = "send_once",
        actionVersion = 2,
        effectId = effectId,
        scope = setOf("message:send"),
        attributedTo = "principal:test",
        parameters = mapOf("message" to "hello"),
        attemptId = "attempt-1",
        attemptNumber = 1
    )

    private fun attestation(
        effect: CapabilityInvocation,
        decision: EffectReconciliationDecision = EffectReconciliationDecision.CONFIRMED_COMPLETED
    ) : EffectReconciliationAttestation {
        val unsigned = EffectReconciliationAttestation( EffectReconciliationAttestation(
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
        reason = "Synthetic test attestation; not provider evidence."
        )
        return unsigned.copy(
            authentication = unsigned.authentication.copy(
                signedDigest = effectReconciliationAttestationSignaturePreimageDigest(unsigned)
            )
        )
    }

    private fun verifier(
        signatureValid: Boolean = true,
        authorityApplicable: Boolean = true,
        evidenceDigestVerified: Boolean = true,
        verifierPolicyValid: Boolean = true,
        principalAuthorized: Boolean = true
    ) = ReconciliationTrustVerifier { attestation, expectedEffect ->
        val digest = effectReconciliationAttestationDigest(attestation)
        TrustVerificationResult(
            attestationRef = "synthetic-attestation:$digest",
            attestationDigest = digest,
            trustPolicyRef = "synthetic-trust-policy:v1",
            signatureValid = signatureValid,
            authorityApplicable = authorityApplicable,
            evidenceDigestVerified = evidenceDigestVerified,
            verifierPolicyValid = verifierPolicyValid,
            principalAuthorized = principalAuthorized
        )
    }

    private fun markUnknown(store: RuntimeStore, effect: CapabilityInvocation) {
        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(effect)))
        assertEquals(EffectTransitionResult.APPLIED, store.markEffectUnknown(effect.effectId))
    }

    @Test
    fun bareDecisionCannotResolveUnknown() {
        val store = InMemoryRuntimeStore()
        val effect = invocation()
        markUnknown(store, effect)

        @Suppress("DEPRECATION")
        val result = store.reconcileEffect(
            effect.effectId,
            EffectReconciliationDecision.CONFIRMED_COMPLETED
        )

        assertEquals(EffectReconciliationResult.ATTESTATION_INVALID, result)
        assertEquals(EffectStatus.UNKNOWN, store.currentEffect(effect.effectId)?.status)
    }

    @Test
    fun missingVerifierFailsClosedAndLeavesUnknown() {
        val store = InMemoryRuntimeStore()
        val effect = invocation()
        markUnknown(store, effect)

        assertEquals(
            EffectReconciliationResult.TRUST_UNAVAILABLE,
            store.reconcileEffect(effect.effectId, attestation(effect))
        )
        assertEquals(EffectStatus.UNKNOWN, store.currentEffect(effect.effectId)?.status)
    }

    @Test
    fun attestationMustMatchExactIdentityAndAttempt() {
        val store = InMemoryRuntimeStore()
        val effect = invocation()
        markUnknown(store, effect)

        val mismatchedIdentity = attestation(effect).copy(
            effectIdentity = attestation(effect).effectIdentity.copy(scope = setOf("different:scope"))
        )
        assertEquals(
            EffectReconciliationResult.ATTESTATION_INVALID,
            store.reconcileEffect(effect.effectId, mismatchedIdentity, verifier())
        )

        val staleAttempt = attestation(effect).copy(attemptId = "older-attempt")
        assertEquals(
            EffectReconciliationResult.STALE_ATTEMPT,
            store.reconcileEffect(effect.effectId, staleAttempt, verifier())
        )
        assertEquals(EffectStatus.UNKNOWN, store.currentEffect(effect.effectId)?.status)
    }

    @Test
    fun allTrustChecksMustPassBeforeTerminalTransition() {
        val store = InMemoryRuntimeStore()
        val effect = invocation()
        markUnknown(store, effect)

        assertEquals(
            EffectReconciliationResult.TRUST_REJECTED,
            store.reconcileEffect(
                effect.effectId,
                attestation(effect),
                verifier(evidenceDigestVerified = false)
            )
        )
        assertEquals(EffectStatus.UNKNOWN, store.currentEffect(effect.effectId)?.status)
    }

    @Test
    fun unresolvedAttestationIsRecordedButDoesNotResolveUnknown() {
        val store = InMemoryRuntimeStore()
        val effect = invocation()
        markUnknown(store, effect)

        assertEquals(
            EffectReconciliationResult.UNRESOLVED,
            store.reconcileEffect(
                effect.effectId,
                attestation(effect, EffectReconciliationDecision.UNRESOLVED),
                verifier()
            )
        )
        val current = store.currentEffect(effect.effectId)
        assertEquals(EffectStatus.UNKNOWN, current?.status)
        assertEquals(
            EffectReconciliationDecision.UNRESOLVED,
            current?.reconciliationAttestation?.decision
        )
    }

    @Test
    fun trustedAttestationAndAuditSurviveJournalRestart() {
        val journal = File.createTempFile("effect-attestation", ".journal")
        journal.deleteOnExit()
        val effect = invocation()
        val store = JournalRuntimeStore(journal)
        markUnknown(store, effect)

        val evidence = attestation(effect)
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            store.reconcileEffect(effect.effectId, evidence, verifier())
        )

        val restored = JournalRuntimeStore(journal).currentEffect(effect.effectId)
        assertEquals(EffectStatus.COMPLETED, restored?.status)
        assertNotNull(restored?.reconciliationAttestation)
        assertEquals(effect.attemptId, restored?.reconciliationAttestation?.attemptId)
        assertEquals(
            effectReconciliationAttestationDigest(evidence),
            restored?.reconciliationAttestation?.attestationDigest
        )
        assertEquals(
            "artifact:synthetic:${effect.effectId}",
            restored?.reconciliationAttestation?.evidenceRef
        )
    }

    @Test
    fun confirmedNotExecutedRequiresFreshAttemptAfterTrustedReconciliation() {
        val store = InMemoryRuntimeStore()
        val effect = invocation()
        markUnknown(store, effect)
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            store.reconcileEffect(
                effect.effectId,
                attestation(effect, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED),
                verifier()
            )
        )

        val retry = effect.copy(attemptId = "attempt-2", attemptNumber = 2)
        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(retry)))
        assertEquals("attempt-2", store.currentEffect(effect.effectId)?.attemptId)
    }

    @Test
    fun separateJournalInstancesSerializeConcurrentReconciliation() {
        val journal = File.createTempFile("effect-concurrent-reconcile", ".journal")
        journal.deleteOnExit()
        val effect = invocation()
        val firstStore = JournalRuntimeStore(journal)
        val secondStore = JournalRuntimeStore(journal)
        markUnknown(firstStore, effect)

        val bothVerifying = CountDownLatch(2)
        val releaseVerifiers = CountDownLatch(1)
        val blockingVerifier = ReconciliationTrustVerifier { evidence, _ ->
            bothVerifying.countDown()
            check(releaseVerifiers.await(10, TimeUnit.SECONDS)) {
                "timed out waiting for competing verifier"
            }
            val digest = effectReconciliationAttestationDigest(evidence)
            TrustVerificationResult(
                attestationRef = "synthetic-concurrent:$digest",
                attestationDigest = digest,
                trustPolicyRef = "synthetic-concurrent-policy:v1",
                signatureValid = true,
                authorityApplicable = true,
                evidenceDigestVerified = true,
                verifierPolicyValid = true,
                principalAuthorized = true
            )
        }
        val evidence = attestation(effect)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<EffectReconciliationResult> {
                firstStore.reconcileEffect(effect.effectId, evidence, blockingVerifier)
            }
            val second = executor.submit<EffectReconciliationResult> {
                secondStore.reconcileEffect(effect.effectId, evidence, blockingVerifier)
            }

            assertTrue("both callers should snapshot UNKNOWN before either verifies", bothVerifying.await(10, TimeUnit.SECONDS))
            releaseVerifiers.countDown()

            val results = listOf(
                first.get(10, TimeUnit.SECONDS),
                second.get(10, TimeUnit.SECONDS)
            )
            assertEquals(
                setOf(EffectReconciliationResult.RECONCILED, EffectReconciliationResult.NOT_UNKNOWN),
                results.toSet()
            )
            assertEquals(1, journal.readLines(Charsets.UTF_8).count { it.startsWith("A|") })
            assertEquals(
                EffectStatus.COMPLETED,
                JournalRuntimeStore(journal).currentEffect(effect.effectId)?.status
            )
        } finally {
            releaseVerifiers.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun journalRejectsLegacyUnauthenticatedUnknownToCompletedTransition() {
        val journal = File.createTempFile("legacy-unauthenticated-reconcile", ".journal")
        journal.deleteOnExit()
        val store = JournalRuntimeStore(journal)
        val effect = invocation()
        markUnknown(store, effect)

        val encodedId = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(effect.effectId.toByteArray(Charsets.UTF_8))
        journal.appendText("X|$encodedId|COMPLETED\\n")

        assertThrows(IllegalStateException::class.java) {
            JournalRuntimeStore(journal).currentEffect(effect.effectId)
        }
    }
}
