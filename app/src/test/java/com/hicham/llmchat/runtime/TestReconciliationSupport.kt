package com.hicham.llmchat.runtime

internal object TestReconciliationSupport {
    fun attestation(
        effect: CapabilityInvocation,
        decision: EffectReconciliationDecision
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
        reason = "Synthetic test record; not provider evidence."
        )
        return unsigned.copy(
            authentication = unsigned.authentication.copy(
                signedDigest = effectReconciliationAttestationSignaturePreimageDigest(unsigned)
            )
        )
    }

    val trustVerifier = ReconciliationTrustVerifier { attestation, _ ->
        val digest = effectReconciliationAttestationDigest(attestation)
        TrustVerificationResult(
            attestationRef = "synthetic-test:$digest",
            attestationDigest = digest,
            trustPolicyRef = "synthetic-test-policy:v1",
            signatureValid = true,
            authorityApplicable = true,
            evidenceDigestVerified = true,
            verifierPolicyValid = true,
            principalAuthorized = true
        )
    }

    fun reconcile(
        store: RuntimeStore,
        effect: CapabilityInvocation,
        decision: EffectReconciliationDecision
    ) = store.reconcileEffect(
        effect.effectId,
        attestation(effect, decision),
        trustVerifier
    )
}
