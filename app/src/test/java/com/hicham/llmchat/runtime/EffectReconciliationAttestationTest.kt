package com.hicham.llmchat.runtime

import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.google.gson.JsonParser
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
        val unsigned = EffectReconciliationAttestation(
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

    private class ArtifactResolvingVerifier : ReconciliationTrustVerifier {
        private val artifacts = ConcurrentHashMap<String, EffectReconciliationAttestation>()
        @Volatile var resolverAvailable = true
        @Volatile var signatureValid = true
        @Volatile var authorityApplicable = true
        @Volatile var evidenceDigestVerified = true
        @Volatile var verifierPolicyValid = true
        @Volatile var principalAuthorized = true

        override fun verify(
            attestation: EffectReconciliationAttestation,
            expectedEffect: EffectRecord
        ): TrustVerificationResult {
            val digest = effectReconciliationAttestationDigest(attestation)
            val ref = "synthetic-attestation:$digest"
            artifacts[ref] = attestation
            return TrustVerificationResult(
                attestationRef = ref,
                attestationDigest = digest,
                trustPolicyRef = "synthetic-trust-policy:v1",
                signatureValid = signatureValid,
                authorityApplicable = authorityApplicable,
                evidenceDigestVerified = evidenceDigestVerified,
                verifierPolicyValid = verifierPolicyValid,
                principalAuthorized = principalAuthorized
            )
        }

        override fun resolve(
            attestationRef: String,
            attestationDigest: String
        ): EffectReconciliationAttestation? {
            if (!resolverAvailable) return null
            // Return the resolved bytes even when modified; the runtime must
            // compare the content digest instead of trusting the resolver's claim.
            return artifacts[attestationRef]
        }

        fun tamper(attestationRef: String) {
            val original = requireNotNull(artifacts[attestationRef])
            artifacts[attestationRef] = original.copy(reason = "tampered after reconciliation")
        }
    }

    private fun markUnknown(store: RuntimeStore, effect: CapabilityInvocation) {
        assertEquals(EffectReservation.RESERVED, store.reserveEffects(listOf(effect)))
        assertEquals(EffectTransitionResult.APPLIED, store.markEffectUnknown(effect.effectId))
    }

    @Test
    fun crossRuntimeAttestationCanonicalizationMatchesGoldenVector() {
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "effect-attestation-canonicalization-v0.1.json"
            )
        )
        val root = JsonParser.parseString(
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        ).asJsonObject
        val source = root.getAsJsonObject("attestation")
        val identity = source.getAsJsonObject("effect_identity")
        val authority = source.getAsJsonObject("authority")
        val evidence = source.getAsJsonObject("evidence")
        val verifier = source.getAsJsonObject("verifier")
        val principal = source.getAsJsonObject("accountable_principal")
        val authentication = source.getAsJsonObject("authentication")

        val attestation = EffectReconciliationAttestation(
            schema = source.get("schema").asString,
            effectIdentity = EffectIdentityAttestation(
                schema = identity.get("schema").asString,
                effectId = identity.get("effect_id").asString,
                runId = identity.get("run_id").asString,
                capabilityId = identity.get("capability_id").asString,
                actionId = identity.get("action_id").asString,
                actionVersion = identity.get("action_version").asInt,
                scope = identity.getAsJsonArray("scope").map { it.asString }.toSet(),
                attributedTo = identity.get("attributed_to").asString,
                parametersDigest = identity.get("parameters_digest").asString
            ),
            attemptId = source.get("attempt_id").asString,
            attemptNumber = source.get("attempt_number").asInt,
            decision = EffectReconciliationDecision.valueOf(source.get("decision").asString),
            decidedAt = source.get("decided_at").asString,
            authority = EffectReconciliationAuthority(
                kind = authority.get("kind").asString,
                ref = authority.get("ref").asString,
                observationRef = authority.get("observation_ref").asString,
                queriedAt = authority.get("queried_at").asString,
                observedAt = authority.get("observed_at").asString
            ),
            evidence = EffectReconciliationEvidence(
                artifactRef = evidence.get("artifact_ref").asString,
                digest = evidence.get("digest").asString
            ),
            verifier = EffectReconciliationVerifierEvidence(
                ref = verifier.get("ref").asString,
                version = verifier.get("version").asString,
                result = verifier.get("result").asString,
                verifiedAt = verifier.get("verified_at").asString
            ),
            accountablePrincipal = EffectAccountablePrincipal(
                kind = principal.get("kind").asString,
                ref = principal.get("ref").asString
            ),
            authentication = EffectAttestationAuthentication(
                scheme = authentication.get("scheme").asString,
                keyRef = authentication.get("key_ref").asString,
                signedDigest = authentication.get("signed_digest").asString,
                signature = authentication.get("signature").asString
            ),
            reason = source.get("reason").asString
        )

        val expected = root.getAsJsonObject("expected")
        val preimage = effectReconciliationAttestationSignaturePreimageBytes(attestation)
        val canonical = effectReconciliationAttestationCanonicalBytes(attestation)
        assertEquals(expected.get("signature_preimage_byte_length").asInt, preimage.size)
        assertEquals(expected.get("attestation_byte_length").asInt, canonical.size)
        assertEquals(
            expected.get("signature_preimage_sha256").asString,
            effectReconciliationAttestationSignaturePreimageDigest(attestation)
        )
        assertEquals(
            expected.get("attestation_sha256").asString,
            effectReconciliationAttestationDigest(attestation)
        )
        assertEquals(
            expected.get("signature_preimage_sha256").asString,
            attestation.authentication.signedDigest
        )
    }

    @Test
    fun canonicalAttestationScopeUsesUtf8ByteOrdering() {
        val original = attestation(invocation())
        val values = listOf("z", "é", "e\u0301", "a")
        val left = original.copy(
            effectIdentity = original.effectIdentity.copy(scope = values.toSet())
        )
        val right = original.copy(
            effectIdentity = original.effectIdentity.copy(scope = values.reversed().toSet())
        )
        assertEquals(
            effectReconciliationAttestationDigest(left),
            effectReconciliationAttestationDigest(right)
        )
        assertEquals(
            effectReconciliationAttestationSignaturePreimageDigest(left),
            effectReconciliationAttestationSignaturePreimageDigest(right)
        )
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
        val trust = ArtifactResolvingVerifier()
        markUnknown(store, effect)
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            store.reconcileEffect(
                effect.effectId,
                attestation(effect, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED),
                trust
            )
        )

        val retry = effect.copy(attemptId = "attempt-2", attemptNumber = 2)
        assertEquals(
            EffectReservation.RESERVED,
            store.reserveEffects(listOf(retry), trust)
        )
        assertEquals("attempt-2", store.currentEffect(effect.effectId)?.attemptId)
        assertEquals(
            "attempt-2",
            store.currentEffect(effect.effectId)?.retryAuthorization?.authorizedAttemptId
        )
    }

    @Test
    fun restoredRetryRequiresResolverAndPersistsExactAuthorization() {
        val journal = File.createTempFile("effect-retry-revalidation", ".journal")
        journal.deleteOnExit()
        val effect = invocation()
        val trust = ArtifactResolvingVerifier()
        val first = JournalRuntimeStore(journal)
        markUnknown(first, effect)
        val evidence = attestation(
            effect,
            EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED
        )
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            first.reconcileEffect(effect.effectId, evidence, trust)
        )

        val retry = effect.copy(attemptId = "attempt-2", attemptNumber = 2)
        val restoredWithoutResolver = JournalRuntimeStore(journal)
        assertEquals(
            EffectReservation.TRUST_UNAVAILABLE,
            restoredWithoutResolver.reserveEffects(listOf(retry))
        )
        assertEquals(
            EffectStatus.CONFIRMED_NOT_EXECUTED,
            restoredWithoutResolver.currentEffect(effect.effectId)?.status
        )

        val restoredWithResolver = JournalRuntimeStore(journal)
        assertEquals(
            EffectReservation.RESERVED,
            restoredWithResolver.reserveEffects(listOf(retry), trust)
        )
        val current = JournalRuntimeStore(journal).currentEffect(effect.effectId)
        assertEquals("attempt-2", current?.attemptId)
        assertEquals(2, current?.attemptNumber)
        val authorization = requireNotNull(current?.retryAuthorization)
        assertEquals("attempt-1", authorization.previousAttemptId)
        assertEquals(1, authorization.previousAttemptNumber)
        assertEquals("attempt-2", authorization.authorizedAttemptId)
        assertEquals(2, authorization.authorizedAttemptNumber)
        assertEquals(
            effectReconciliationAttestationDigest(evidence),
            authorization.attestationDigest
        )
        assertEquals(
            current?.reconciliationAttestation?.attestationRef,
            authorization.attestationRef
        )
        assertEquals("synthetic-trust-policy:v1", authorization.trustPolicyRef)
    }

    @Test
    fun retryFailsClosedWhenAttestationArtifactIsMissingOrTampered() {
        val journal = File.createTempFile("effect-retry-unresolvable", ".journal")
        journal.deleteOnExit()
        val effect = invocation()
        val trust = ArtifactResolvingVerifier()
        val store = JournalRuntimeStore(journal)
        markUnknown(store, effect)
        val evidence = attestation(
            effect,
            EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED
        )
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            store.reconcileEffect(effect.effectId, evidence, trust)
        )
        val retry = effect.copy(attemptId = "attempt-2", attemptNumber = 2)
        val before = journal.readLines(Charsets.UTF_8).size

        trust.resolverAvailable = false
        assertEquals(
            EffectReservation.RECONCILIATION_REVALIDATION_REQUIRED,
            JournalRuntimeStore(journal).reserveEffects(listOf(retry), trust)
        )
        trust.resolverAvailable = true
        val ref = requireNotNull(store.currentEffect(effect.effectId)?.reconciliationAttestation).attestationRef
        trust.tamper(ref)
        assertEquals(
            EffectReservation.TRUST_REJECTED,
            JournalRuntimeStore(journal).reserveEffects(listOf(retry), trust)
        )
        assertEquals(before, journal.readLines(Charsets.UTF_8).size)
        assertEquals(
            EffectStatus.CONFIRMED_NOT_EXECUTED,
            JournalRuntimeStore(journal).currentEffect(effect.effectId)?.status
        )
    }

    @Test
    fun retryFailsClosedWhenTrustIsRevokedAfterRestore() {
        val journal = File.createTempFile("effect-retry-revoked", ".journal")
        journal.deleteOnExit()
        val effect = invocation()
        val trust = ArtifactResolvingVerifier()
        val store = JournalRuntimeStore(journal)
        markUnknown(store, effect)
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            store.reconcileEffect(
                effect.effectId,
                attestation(effect, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED),
                trust
            )
        )
        trust.signatureValid = false
        val retry = effect.copy(attemptId = "attempt-2", attemptNumber = 2)

        assertEquals(
            EffectReservation.TRUST_REJECTED,
            JournalRuntimeStore(journal).reserveEffects(listOf(retry), trust)
        )
        assertEquals(
            EffectStatus.CONFIRMED_NOT_EXECUTED,
            JournalRuntimeStore(journal).currentEffect(effect.effectId)?.status
        )
    }

    @Test
    fun everyFreshTrustAssertionIsRequiredBeforeRetry() {
        val cases: List<Pair<String, (ArtifactResolvingVerifier) -> Unit>> = listOf(
            "signature" to { it.signatureValid = false },
            "authority" to { it.authorityApplicable = false },
            "evidence digest" to { it.evidenceDigestVerified = false },
            "verifier policy" to { it.verifierPolicyValid = false },
            "principal authorization" to { it.principalAuthorized = false }
        )

        cases.forEachIndexed { index, caseItem ->
            val journal = File.createTempFile("effect-retry-trust-$index", ".journal")
            journal.deleteOnExit()
            val effect = invocation("effect-retry-trust-$index")
            val trust = ArtifactResolvingVerifier()
            val store = JournalRuntimeStore(journal)
            markUnknown(store, effect)
            val evidence = attestation(
                effect,
                EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED
            )
            assertEquals(
                EffectReconciliationResult.RECONCILED,
                store.reconcileEffect(effect.effectId, evidence, trust)
            )

            caseItem.second(trust)
            val retry = effect.copy(
                attemptId = "attempt-retry-trust-$index-2",
                attemptNumber = 2
            )
            val beforeCount = journal.readLines(Charsets.UTF_8).size
            assertEquals(
                "retry must fail when ${caseItem.first} trust is rejected",
                EffectReservation.TRUST_REJECTED,
                JournalRuntimeStore(journal).reserveEffects(listOf(retry), trust)
            )
            assertEquals(beforeCount, journal.readLines(Charsets.UTF_8).size)
            assertEquals(
                EffectStatus.CONFIRMED_NOT_EXECUTED,
                JournalRuntimeStore(journal).currentEffect(effect.effectId)?.status
            )
        }
    }

    @Test
    fun replayRejectsAlteredOrIncompleteE4RetryAuthorization() {
        val journal = File.createTempFile("effect-retry-e4-corrupt", ".journal")
        journal.deleteOnExit()
        val effect = invocation("effect-retry-e4-corrupt")
        val trust = ArtifactResolvingVerifier()
        val store = JournalRuntimeStore(journal)
        markUnknown(store, effect)
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            store.reconcileEffect(
                effect.effectId,
                attestation(effect, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED),
                trust
            )
        )
        val retry = effect.copy(attemptId = "attempt-e4-2", attemptNumber = 2)
        assertEquals(
            EffectReservation.RESERVED,
            JournalRuntimeStore(journal).reserveEffects(listOf(retry), trust)
        )

        val records = journal.readLines(Charsets.UTF_8).toMutableList()
        val retryIndex = records.indexOfLast { it.startsWith("E4|") }
        assertTrue("retry must be represented by an E4 event", retryIndex >= 0)
        val parts = records[retryIndex].split('|').toMutableList()
        assertEquals(21, parts.size)
        parts[16] = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(("sha256:" + "f".repeat(64)).toByteArray(Charsets.UTF_8))
        records[retryIndex] = parts.joinToString("|")
        journal.writeText(records.joinToString("\n", postfix = "\n"), Charsets.UTF_8)

        assertThrows(IllegalStateException::class.java) {
            JournalRuntimeStore(journal).currentEffect(effect.effectId)
        }

        val incomplete = File.createTempFile("effect-retry-e4-incomplete", ".journal")
        incomplete.deleteOnExit()
        val cleanJournal = File.createTempFile("effect-retry-e4-clean", ".journal")
        cleanJournal.deleteOnExit()
        val cleanEffect = invocation("effect-retry-e4-incomplete")
        val cleanTrust = ArtifactResolvingVerifier()
        val cleanStore = JournalRuntimeStore(cleanJournal)
        markUnknown(cleanStore, cleanEffect)
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            cleanStore.reconcileEffect(
                cleanEffect.effectId,
                attestation(cleanEffect, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED),
                cleanTrust
            )
        )
        val cleanRetry = cleanEffect.copy(attemptId = "attempt-e4-incomplete-2", attemptNumber = 2)
        assertEquals(
            EffectReservation.RESERVED,
            JournalRuntimeStore(cleanJournal).reserveEffects(listOf(cleanRetry), cleanTrust)
        )
        val incompleteRecords = cleanJournal.readLines(Charsets.UTF_8).toMutableList()
        val incompleteIndex = incompleteRecords.indexOfLast { it.startsWith("E4|") }
        val incompleteParts = incompleteRecords[incompleteIndex].split('|')
        incompleteRecords[incompleteIndex] = incompleteParts.dropLast(1).joinToString("|")
        incomplete.writeText(incompleteRecords.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        assertThrows(IllegalStateException::class.java) {
            JournalRuntimeStore(incomplete).currentEffect(cleanEffect.effectId)
        }
    }

    @Test
    fun competingRestoredRetriesRevalidateOutsideLockAndOnlyOneReservationWins() {
        val journal = File.createTempFile("effect-retry-race", ".journal")
        journal.deleteOnExit()
        val effect = invocation()
        val artifactStore = ArtifactResolvingVerifier()
        val first = JournalRuntimeStore(journal)
        markUnknown(first, effect)
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            first.reconcileEffect(
                effect.effectId,
                attestation(effect, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED),
                artifactStore
            )
        )

        val bothVerifying = CountDownLatch(2)
        val releaseVerifiers = CountDownLatch(1)
        val blockingVerifier = object : ReconciliationTrustVerifier {
            override fun resolve(
                attestationRef: String,
                attestationDigest: String
            ): EffectReconciliationAttestation? =
                artifactStore.resolve(attestationRef, attestationDigest)

            override fun verify(
                evidence: EffectReconciliationAttestation,
                expectedEffect: EffectRecord
            ): TrustVerificationResult {
                bothVerifying.countDown()
                check(releaseVerifiers.await(10, TimeUnit.SECONDS)) {
                    "timed out waiting for competing retry verifiers"
                }
                return artifactStore.verify(evidence, expectedEffect)
            }
        }

        val retry = effect.copy(attemptId = "attempt-2", attemptNumber = 2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstFuture = executor.submit<EffectReservation> {
                JournalRuntimeStore(journal).reserveEffects(listOf(retry), blockingVerifier)
            }
            val secondFuture = executor.submit<EffectReservation> {
                JournalRuntimeStore(journal).reserveEffects(listOf(retry), blockingVerifier)
            }

            assertTrue(
                "both callers must reach trust verification without holding the journal lock",
                bothVerifying.await(10, TimeUnit.SECONDS)
            )
            releaseVerifiers.countDown()

            val results = listOf(
                firstFuture.get(10, TimeUnit.SECONDS),
                secondFuture.get(10, TimeUnit.SECONDS)
            )
            assertEquals(1, results.count { it == EffectReservation.RESERVED })
            assertEquals(1, results.count { it == EffectReservation.RETRY_AUTHORIZATION_STALE })
            assertEquals(
                1,
                journal.readLines(Charsets.UTF_8).count { it.startsWith("E4|") }
            )
            assertEquals(
                "attempt-2",
                JournalRuntimeStore(journal).currentEffect(effect.effectId)?.attemptId
            )
        } finally {
            releaseVerifiers.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun interruptedRetryReservationRestoresAsUnknownAndCannotBeBlindlyRepeated() {
        val journal = File.createTempFile("effect-retry-interrupted", ".journal")
        journal.deleteOnExit()
        val effect = invocation()
        val trust = ArtifactResolvingVerifier()
        val first = JournalRuntimeStore(journal)
        markUnknown(first, effect)
        assertEquals(
            EffectReconciliationResult.RECONCILED,
            first.reconcileEffect(
                effect.effectId,
                attestation(effect, EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED),
                trust
            )
        )

        val retry = effect.copy(attemptId = "attempt-2", attemptNumber = 2)
        assertEquals(
            EffectReservation.RESERVED,
            JournalRuntimeStore(journal).reserveEffects(listOf(retry), trust)
        )

        val restored = JournalRuntimeStore(journal)
        assertEquals(1, restored.recoverInterruptedEffects())
        val interrupted = JournalRuntimeStore(journal).currentEffect(effect.effectId)
        assertEquals(EffectStatus.UNKNOWN, interrupted?.status)
        assertEquals("attempt-2", interrupted?.attemptId)
        assertEquals(
            EffectReservation.REPLAY_BLOCKED,
            JournalRuntimeStore(journal).reserveEffects(
                listOf(effect.copy(attemptId = "attempt-3", attemptNumber = 3)),
                trust
            )
        )
        assertEquals(1, journal.readLines(Charsets.UTF_8).count { it.startsWith("E4|") })
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
