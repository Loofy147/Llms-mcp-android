package com.hicham.llmchat.runtime

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.time.OffsetDateTime
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Durable runtime boundary. Implementations must serialize state before returning. */
interface RuntimeStore {
    fun saveRun(run: Run)
    fun loadRun(runId: String, catalog: ActionCatalog): Run?
    fun reserveEffects(
        invocations: List<CapabilityInvocation>,
        retryTrustVerifier: ReconciliationTrustVerifier? = null
    ): EffectReservation
    fun completeEffect(effectId: String): EffectTransitionResult
    fun markEffectUnknown(effectId: String): EffectTransitionResult

    /** Legacy call shape is retained only to fail closed; a bare decision is not evidence. */
    @Deprecated("Bare decisions cannot reconcile UNKNOWN. Supply an attestation and trust verifier.")
    fun reconcileEffect(
        effectId: String,
        decision: EffectReconciliationDecision
    ): EffectReconciliationResult = EffectReconciliationResult.ATTESTATION_INVALID

    fun reconcileEffect(
        effectId: String,
        attestation: EffectReconciliationAttestation,
        trustVerifier: ReconciliationTrustVerifier? = null
    ): EffectReconciliationResult

    fun currentEffect(effectId: String): EffectRecord?
    fun unknownEffects(): List<EffectRecord>
    /** Convert effects that were reserved before an interrupted process into explicitly reconcilable UNKNOWN state. */
    fun recoverInterruptedEffects(): Int
}

enum class EffectReservation {
    RESERVED,
    REPLAY_BLOCKED,
    CONFLICT,
    TRUST_UNAVAILABLE,
    TRUST_REJECTED,
    RECONCILIATION_REVALIDATION_REQUIRED,
    RETRY_AUTHORIZATION_STALE
}
enum class EffectTransitionResult { APPLIED, NOT_FOUND, INVALID_TRANSITION }
enum class EffectReconciliationDecision { CONFIRMED_COMPLETED, CONFIRMED_NOT_EXECUTED, UNRESOLVED }
enum class EffectReconciliationResult {
    RECONCILED, NOT_FOUND, NOT_UNKNOWN, ATTESTATION_INVALID,
    TRUST_UNAVAILABLE, TRUST_REJECTED, STALE_ATTEMPT, UNRESOLVED
}
enum class EffectStatus { RESERVED, COMPLETED, UNKNOWN, CONFIRMED_NOT_EXECUTED }

data class EffectIdentityAttestation(
    val schema: String,
    val effectId: String,
    val runId: String,
    val capabilityId: String,
    val actionId: String,
    val actionVersion: Int,
    val scope: Set<String>,
    val attributedTo: String,
    val parametersDigest: String
)

data class EffectReconciliationAuthority(
    val kind: String,
    val ref: String,
    val observationRef: String,
    val queriedAt: String,
    val observedAt: String
)

data class EffectReconciliationEvidence(
    val artifactRef: String,
    val digest: String
)

data class EffectReconciliationVerifierEvidence(
    val ref: String,
    val version: String,
    val result: String,
    val verifiedAt: String
)

data class EffectAccountablePrincipal(
    val kind: String,
    val ref: String
)

data class EffectAttestationAuthentication(
    val scheme: String,
    val keyRef: String,
    val signedDigest: String,
    val signature: String
)

data class EffectReconciliationAttestation(
    val schema: String,
    val effectIdentity: EffectIdentityAttestation,
    val attemptId: String,
    val attemptNumber: Int,
    val decision: EffectReconciliationDecision,
    val decidedAt: String,
    val authority: EffectReconciliationAuthority,
    val evidence: EffectReconciliationEvidence,
    val verifier: EffectReconciliationVerifierEvidence,
    val accountablePrincipal: EffectAccountablePrincipal,
    val authentication: EffectAttestationAuthentication,
    val reason: String
)

data class TrustVerificationResult(
    val attestationRef: String,
    val attestationDigest: String,
    val trustPolicyRef: String,
    val signatureValid: Boolean,
    val authorityApplicable: Boolean,
    val evidenceDigestVerified: Boolean,
    val verifierPolicyValid: Boolean,
    val principalAuthorized: Boolean
)

fun interface ReconciliationTrustVerifier {
    fun verify(
        attestation: EffectReconciliationAttestation,
        expectedEffect: EffectRecord
    ): TrustVerificationResult

    /**
     * Resolve the complete, immutable attestation by its previously trusted reference
     * and digest. The default deliberately fails closed for retry authorization while
     * retaining SAM compatibility for existing verifier implementations.
     */
    fun resolve(
        attestationRef: String,
        attestationDigest: String
    ): EffectReconciliationAttestation? = null
}

/** Audit pointer appended after a trusted reconciliation; the full signed attestation remains addressable by digest/ref. */
data class EffectReconciliationAudit(
    val attestationRef: String,
    val attestationDigest: String,
    val trustPolicyRef: String,
    val attemptId: String,
    val attemptNumber: Int,
    val decision: EffectReconciliationDecision,
    val authorityRef: String,
    val observationRef: String,
    val evidenceRef: String,
    val evidenceDigest: String,
    val verifierRef: String,
    val verifierVersion: String,
    val principalKind: String,
    val principalRef: String
)

data class EffectRetryAuthorization(
    val previousAttemptId: String,
    val previousAttemptNumber: Int,
    val attestationRef: String,
    val attestationDigest: String,
    val trustPolicyRef: String,
    val authorizedAttemptId: String,
    val authorizedAttemptNumber: Int,
    val revalidatedAt: String
)

data class EffectRecord(
    val effectId: String,
    val runId: String,
    val capabilityId: String,
    val actionId: String,
    val actionVersion: Int,
    val scope: Set<String>,
    val attributedTo: String,
    val parameters: Map<String, String>,
    val attemptId: String,
    val attemptNumber: Int,
    val parametersDigest: String,
    val status: EffectStatus,
    val reconciliationAttestation: EffectReconciliationAudit? = null,
    val retryAuthorization: EffectRetryAuthorization? = null
)

/**
 * Digest over a stable, unambiguous representation of the parameter map.
 * Length prefixes are UTF-8 byte lengths, and keys are sorted ordinally.
 */
private fun compareUnsignedUtf8(left: String, right: String): Int {
    val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
    val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
    val sharedLength = minOf(leftBytes.size, rightBytes.size)
    for (index in 0 until sharedLength) {
        val comparison = (leftBytes[index].toInt() and 0xff)
            .compareTo(rightBytes[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return leftBytes.size.compareTo(rightBytes.size)
}

private fun canonicalParametersDigest(parameters: Map<String, String>): String {
    val canonical = buildString {
        parameters.keys.sortedWith(Comparator(::compareUnsignedUtf8)).forEach { key ->
            val value = parameters.getValue(key)
            append(key.toByteArray(StandardCharsets.UTF_8).size)
            append(':')
            append(key)
            append(value.toByteArray(StandardCharsets.UTF_8).size)
            append(':')
            append(value)
        }
    }.toByteArray(StandardCharsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical)
    val hex = digest.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    return "sha256:$hex"
}

internal fun canonicalEffectParametersDigest(parameters: Map<String, String>): String =
    canonicalParametersDigest(parameters)

private const val EFFECT_ATTESTATION_CANONICALIZATION_ID =
    "open_system_one.effect_reconciliation_attestation_canonical.v0.1"

private fun compareUtf8(left: String, right: String): Int {
    val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
    val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
    val limit = minOf(leftBytes.size, rightBytes.size)
    for (index in 0 until limit) {
        val leftByte = leftBytes[index].toInt() and 0xff
        val rightByte = rightBytes[index].toInt() and 0xff
        if (leftByte != rightByte) return leftByte.compareTo(rightByte)
    }
    return leftBytes.size.compareTo(rightBytes.size)
}

private fun effectReconciliationCanonicalFields(
    attestation: EffectReconciliationAttestation,
    includeSignatureFields: Boolean
): List<String> {
    val identity = attestation.effectIdentity
    val authentication = attestation.authentication
    val scope = identity.scope.sortedWith(::compareUtf8)
    val fields = mutableListOf(
        EFFECT_ATTESTATION_CANONICALIZATION_ID,
        attestation.schema,
        identity.schema,
        identity.effectId,
        identity.runId,
        identity.capabilityId,
        identity.actionId,
        identity.actionVersion.toString(),
        scope.size.toString()
    )
    fields.addAll(scope)
    fields.addAll(
        listOf(
            identity.attributedTo,
            identity.parametersDigest,
            attestation.attemptId,
            attestation.attemptNumber.toString(),
            attestation.decision.name,
            attestation.decidedAt,
            attestation.authority.kind,
            attestation.authority.ref,
            attestation.authority.observationRef,
            attestation.authority.queriedAt,
            attestation.authority.observedAt,
            attestation.evidence.artifactRef,
            attestation.evidence.digest,
            attestation.verifier.ref,
            attestation.verifier.version,
            attestation.verifier.result,
            attestation.verifier.verifiedAt,
            attestation.accountablePrincipal.kind,
            attestation.accountablePrincipal.ref,
            authentication.scheme,
            authentication.keyRef
        )
    )
    if (includeSignatureFields) {
        fields.add(authentication.signedDigest)
        fields.add(authentication.signature)
    }
    fields.add(attestation.reason)
    return fields
}

private fun encodeAttestationFields(fields: List<String>): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    fields.forEach { value ->
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        output.write(encoded.size.toString().toByteArray(StandardCharsets.US_ASCII))
        output.write(':'.code)
        output.write(encoded)
    }
    return output.toByteArray()
}

internal fun effectReconciliationAttestationSignaturePreimageBytes(
    attestation: EffectReconciliationAttestation
): ByteArray = encodeAttestationFields(
    effectReconciliationCanonicalFields(attestation, includeSignatureFields = false)
)

internal fun effectReconciliationAttestationSignaturePreimageDigest(
    attestation: EffectReconciliationAttestation
): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(effectReconciliationAttestationSignaturePreimageBytes(attestation))
    val hex = digest.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    return "sha256:$hex"
}

internal fun effectReconciliationAttestationCanonicalBytes(
    attestation: EffectReconciliationAttestation
): ByteArray = encodeAttestationFields(
    effectReconciliationCanonicalFields(attestation, includeSignatureFields = true)
)

internal fun effectReconciliationAttestationDigest(
    attestation: EffectReconciliationAttestation
): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(effectReconciliationAttestationCanonicalBytes(attestation))
    val hex = digest.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    return "sha256:$hex"
}

private fun validOffsetTimestamp(value: String): Boolean =
    runCatching { OffsetDateTime.parse(value) }.isSuccess

private fun identityMatches(record: EffectRecord, identity: EffectIdentityAttestation): Boolean =
    identity == EffectIdentityAttestation(
        schema = "open_system_one.effect_identity.v0.1",
        effectId = record.effectId,
        runId = record.runId,
        capabilityId = record.capabilityId,
        actionId = record.actionId,
        actionVersion = record.actionVersion,
        scope = record.scope,
        attributedTo = record.attributedTo,
        parametersDigest = record.parametersDigest
    )

private fun sameIdentityAndAttempt(left: EffectRecord, right: EffectRecord): Boolean =
    left.effectId == right.effectId &&
        left.runId == right.runId &&
        left.capabilityId == right.capabilityId &&
        left.actionId == right.actionId &&
        left.actionVersion == right.actionVersion &&
        left.scope == right.scope &&
        left.attributedTo == right.attributedTo &&
        left.parameters == right.parameters &&
        left.parametersDigest == right.parametersDigest &&
        left.attemptId == right.attemptId &&
        left.attemptNumber == right.attemptNumber

private fun validateReconciliationAttestation(
    attestation: EffectReconciliationAttestation,
    expected: EffectRecord
): EffectReconciliationResult? {
    if (attestation.schema != "open_system_one.effect_reconciliation_attestation.v0.1") {
        return EffectReconciliationResult.ATTESTATION_INVALID
    }
    if (!identityMatches(expected, attestation.effectIdentity)) {
        return EffectReconciliationResult.ATTESTATION_INVALID
    }
    if (attestation.attemptId.isBlank() || attestation.attemptNumber < 1) {
        return EffectReconciliationResult.ATTESTATION_INVALID
    }
    if (
        attestation.attemptId != expected.attemptId ||
        attestation.attemptNumber != expected.attemptNumber
    ) {
        return EffectReconciliationResult.STALE_ATTEMPT
    }
    if (!validOffsetTimestamp(attestation.decidedAt)) {
        return EffectReconciliationResult.ATTESTATION_INVALID
    }

    val authority = attestation.authority
    if (
        authority.kind.isBlank() || authority.ref.isBlank() ||
        authority.observationRef.isBlank() ||
        !validOffsetTimestamp(authority.queriedAt) ||
        !validOffsetTimestamp(authority.observedAt)
    ) {
        return EffectReconciliationResult.ATTESTATION_INVALID
    }

    val evidence = attestation.evidence
    if (
        evidence.artifactRef.isBlank() ||
        !Regex("^sha256:[0-9a-f]{64}$").matches(evidence.digest)
    ) {
        return EffectReconciliationResult.ATTESTATION_INVALID
    }

    val verifier = attestation.verifier
    if (
        verifier.ref.isBlank() || verifier.version.isBlank() ||
        !validOffsetTimestamp(verifier.verifiedAt)
    ) {
        return EffectReconciliationResult.ATTESTATION_INVALID
    }
    val expectedVerifierResult = when (attestation.decision) {
        EffectReconciliationDecision.CONFIRMED_COMPLETED,
        EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED -> setOf("PASS")
        EffectReconciliationDecision.UNRESOLVED -> setOf("FAIL", "INCONCLUSIVE")
    }
    if (verifier.result !in expectedVerifierResult) {
        return EffectReconciliationResult.ATTESTATION_INVALID
    }

    val principal = attestation.accountablePrincipal
    if (principal.kind !in setOf("human", "service") || principal.ref.isBlank()) {
        return EffectReconciliationResult.ATTESTATION_INVALID
    }

    val authentication = attestation.authentication
    if (
        authentication.scheme !in setOf("ed25519", "jws") ||
        authentication.keyRef.isBlank() ||
        !Regex("^sha256:[0-9a-f]{64}$").matches(authentication.signedDigest) ||
        authentication.signature.isBlank() ||
        attestation.reason.isBlank()
    ) {
        return EffectReconciliationResult.ATTESTATION_INVALID
    }
    if (
        authentication.signedDigest !=
        effectReconciliationAttestationSignaturePreimageDigest(attestation)
    ) {
        return EffectReconciliationResult.ATTESTATION_INVALID
    }
    return null
}

private fun prepareReconciliation(
    attestation: EffectReconciliationAttestation,
    expected: EffectRecord,
    trustVerifier: ReconciliationTrustVerifier?
): Pair<EffectReconciliationResult?, EffectReconciliationAudit?> {
    validateReconciliationAttestation(attestation, expected)?.let { return it to null }
    if (trustVerifier == null) return EffectReconciliationResult.TRUST_UNAVAILABLE to null

    val digestBefore = effectReconciliationAttestationDigest(attestation)
    val verified = runCatching {
        trustVerifier.verify(attestation, expected)
    }.getOrNull() ?: return EffectReconciliationResult.TRUST_REJECTED to null

    // The attestation can contain caller-owned collections; reject mutation during trust evaluation.
    val digestAfter = runCatching {
        effectReconciliationAttestationDigest(attestation)
    }.getOrNull()
    if (
        digestAfter != digestBefore ||
        verified.attestationDigest != digestBefore ||
        verified.attestationRef.isBlank() ||
        verified.trustPolicyRef.isBlank() ||
        verified.signatureValid != true ||
        verified.authorityApplicable != true ||
        verified.evidenceDigestVerified != true ||
        verified.verifierPolicyValid != true ||
        verified.principalAuthorized != true
    ) {
        return EffectReconciliationResult.TRUST_REJECTED to null
    }

    return null to EffectReconciliationAudit(
        attestationRef = verified.attestationRef,
        attestationDigest = digestBefore,
        trustPolicyRef = verified.trustPolicyRef,
        attemptId = attestation.attemptId,
        attemptNumber = attestation.attemptNumber,
        decision = attestation.decision,
        authorityRef = attestation.authority.ref,
        observationRef = attestation.authority.observationRef,
        evidenceRef = attestation.evidence.artifactRef,
        evidenceDigest = attestation.evidence.digest,
        verifierRef = attestation.verifier.ref,
        verifierVersion = attestation.verifier.version,
        principalKind = attestation.accountablePrincipal.kind,
        principalRef = attestation.accountablePrincipal.ref
    )
}

private fun prepareRetryAuthorization(
    previous: EffectRecord,
    nextAttempt: CapabilityInvocation,
    trustVerifier: ReconciliationTrustVerifier?
): Pair<EffectReservation?, EffectRetryAuthorization?> {
    if (trustVerifier == null) return EffectReservation.TRUST_UNAVAILABLE to null

    val priorAudit = previous.reconciliationAttestation
        ?: return EffectReservation.RECONCILIATION_REVALIDATION_REQUIRED to null
    if (
        previous.status != EffectStatus.CONFIRMED_NOT_EXECUTED ||
        priorAudit.decision != EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED ||
        priorAudit.attemptId != previous.attemptId ||
        priorAudit.attemptNumber != previous.attemptNumber
    ) {
        return EffectReservation.RECONCILIATION_REVALIDATION_REQUIRED to null
    }

    val resolved = runCatching {
        trustVerifier.resolve(priorAudit.attestationRef, priorAudit.attestationDigest)
    }.getOrNull() ?: return EffectReservation.RECONCILIATION_REVALIDATION_REQUIRED to null

    val resolvedDigest = runCatching {
        effectReconciliationAttestationDigest(resolved)
    }.getOrNull()
    if (resolvedDigest != priorAudit.attestationDigest) {
        return EffectReservation.TRUST_REJECTED to null
    }
    if (resolved.decision != EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED) {
        return EffectReservation.TRUST_REJECTED to null
    }

    val (error, refreshedAudit) = prepareReconciliation(
        resolved,
        previous,
        trustVerifier
    )
    if (error != null) {
        return EffectReservation.TRUST_REJECTED to null
    }
    val refreshed = requireNotNull(refreshedAudit)
    if (
        refreshed.attestationRef != priorAudit.attestationRef ||
        refreshed.attestationDigest != priorAudit.attestationDigest ||
        refreshed.decision != EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED
    ) {
        return EffectReservation.TRUST_REJECTED to null
    }

    return null to EffectRetryAuthorization(
        previousAttemptId = previous.attemptId,
        previousAttemptNumber = previous.attemptNumber,
        attestationRef = refreshed.attestationRef,
        attestationDigest = refreshed.attestationDigest,
        trustPolicyRef = refreshed.trustPolicyRef,
        authorizedAttemptId = nextAttempt.attemptId,
        authorizedAttemptNumber = nextAttempt.attemptNumber,
        revalidatedAt = OffsetDateTime.now().toString()
    )
}

private fun validRetryAuthorization(
    authorization: EffectRetryAuthorization,
    previous: StoredEffect,
    next: StoredEffect
): Boolean {
    val priorAudit = previous.reconciliationAttestation ?: return false
    return previous.status == EffectStatus.CONFIRMED_NOT_EXECUTED &&
        priorAudit.decision == EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED &&
        priorAudit.attemptId == previous.attemptId &&
        priorAudit.attemptNumber == previous.attemptNumber &&
        authorization.previousAttemptId == previous.attemptId &&
        authorization.previousAttemptNumber == previous.attemptNumber &&
        authorization.attestationRef == priorAudit.attestationRef &&
        authorization.attestationDigest == priorAudit.attestationDigest &&
        authorization.trustPolicyRef.isNotBlank() &&
        authorization.authorizedAttemptId == next.attemptId &&
        authorization.authorizedAttemptNumber == next.attemptNumber &&
        next.attemptNumber == previous.attemptNumber + 1 &&
        validOffsetTimestamp(authorization.revalidatedAt)
}

private data class StoredEffect(
    val effectId: String,
    val runId: String,
    val capabilityId: String,
    val actionId: String,
    val actionVersion: Int,
    val scope: Set<String>,
    val attributedTo: String,
    val parameters: Map<String, String>,
    val attemptId: String,
    val attemptNumber: Int,
    val parametersDigest: String,
    val status: EffectStatus,
    val reconciliationAttestation: EffectReconciliationAudit? = null,
    val retryAuthorization: EffectRetryAuthorization? = null
) {
    fun hasValidIdentity(): Boolean =
        effectId.isNotBlank() &&
            runId.isNotBlank() &&
            capabilityId.isNotBlank() &&
            actionId.isNotBlank() &&
            actionVersion > 0 &&
            attributedTo.isNotBlank() &&
            attemptId.isNotBlank() &&
            attemptNumber > 0 &&
            scope.none { it.isBlank() } &&
            parameters.keys.none { it.isBlank() } &&
            parametersDigest == canonicalParametersDigest(parameters)

    /** Attempt identity is intentionally excluded; all other fields are immutable. */
    fun compatibleWith(other: StoredEffect): Boolean =
        effectId == other.effectId &&
            runId == other.runId &&
            capabilityId == other.capabilityId &&
            actionId == other.actionId &&
            actionVersion == other.actionVersion &&
            scope == other.scope &&
            attributedTo == other.attributedTo &&
            parametersDigest == other.parametersDigest &&
            parameters == other.parameters

    fun record() = EffectRecord(
        effectId, runId, capabilityId, actionId, actionVersion, scope,
        attributedTo, parameters, attemptId, attemptNumber, parametersDigest, status,
        reconciliationAttestation, retryAuthorization
    )

    /** E3 is a normal reservation; E4 binds a retry to fresh trust revalidation. */
    fun encode(): String {
        val fields = listOf(
            Codec.encode(effectId),
            Codec.encode(runId),
            Codec.encode(capabilityId),
            Codec.encode(actionId),
            actionVersion.toString(),
            Codec.encodeSet(scope),
            Codec.encode(attributedTo),
            Codec.encodeMap(parameters),
            Codec.encode(parametersDigest),
            Codec.encode(attemptId),
            attemptNumber.toString(),
            status.name
        )
        val authorization = retryAuthorization
            ?: return (listOf("E3") + fields).joinToString("|")
        return (
            listOf("E4") + fields + listOf(
                Codec.encode(authorization.previousAttemptId),
                authorization.previousAttemptNumber.toString(),
                Codec.encode(authorization.attestationRef),
                Codec.encode(authorization.attestationDigest),
                Codec.encode(authorization.trustPolicyRef),
                Codec.encode(authorization.authorizedAttemptId),
                authorization.authorizedAttemptNumber.toString(),
                Codec.encode(authorization.revalidatedAt)
            )
        ).joinToString("|")
    }

    companion object {
        fun from(invocation: CapabilityInvocation, status: EffectStatus) = StoredEffect(
            effectId = invocation.effectId,
            runId = invocation.runId,
            capabilityId = invocation.capabilityId,
            actionId = invocation.actionId,
            actionVersion = invocation.actionVersion,
            scope = invocation.scope.toSet(),
            attributedTo = invocation.attributedTo,
            parameters = invocation.parameters.toMap(),
            attemptId = invocation.attemptId,
            attemptNumber = invocation.attemptNumber,
            parametersDigest = canonicalParametersDigest(invocation.parameters),
            status = status
        )

        fun decode(parts: List<String>, legacyAttemptId: String): StoredEffect? = runCatching {
            when {
                parts.size == 10 && parts[0] == "E" -> {
                    val parameters = Codec.decodeMap(parts[8])
                    StoredEffect(
                        effectId = Codec.decode(parts[1]),
                        runId = Codec.decode(parts[2]),
                        capabilityId = Codec.decode(parts[3]),
                        actionId = Codec.decode(parts[4]),
                        actionVersion = parts[5].toInt(),
                        scope = Codec.decodeSet(parts[6]),
                        attributedTo = Codec.decode(parts[7]),
                        parameters = parameters,
                        attemptId = legacyAttemptId,
                        attemptNumber = 1,
                        parametersDigest = canonicalParametersDigest(parameters),
                        status = EffectStatus.valueOf(parts[9])
                    )
                }
                parts.size == 12 && parts[0] == "E2" -> StoredEffect(
                    effectId = Codec.decode(parts[1]),
                    runId = Codec.decode(parts[2]),
                    capabilityId = Codec.decode(parts[3]),
                    actionId = Codec.decode(parts[4]),
                    actionVersion = parts[5].toInt(),
                    scope = Codec.decodeSet(parts[6]),
                    attributedTo = Codec.decode(parts[7]),
                    parameters = Codec.decodeMap(parts[8]),
                    parametersDigest = Codec.decode(parts[9]),
                    attemptId = Codec.decode(parts[10]),
                    attemptNumber = 1,
                    status = EffectStatus.valueOf(parts[11])
                )
                parts.size == 13 && parts[0] == "E3" -> StoredEffect(
                    effectId = Codec.decode(parts[1]),
                    runId = Codec.decode(parts[2]),
                    capabilityId = Codec.decode(parts[3]),
                    actionId = Codec.decode(parts[4]),
                    actionVersion = parts[5].toInt(),
                    scope = Codec.decodeSet(parts[6]),
                    attributedTo = Codec.decode(parts[7]),
                    parameters = Codec.decodeMap(parts[8]),
                    parametersDigest = Codec.decode(parts[9]),
                    attemptId = Codec.decode(parts[10]),
                    attemptNumber = parts[11].toInt(),
                    status = EffectStatus.valueOf(parts[12])
                )
                parts.size == 21 && parts[0] == "E4" -> StoredEffect(
                    effectId = Codec.decode(parts[1]),
                    runId = Codec.decode(parts[2]),
                    capabilityId = Codec.decode(parts[3]),
                    actionId = Codec.decode(parts[4]),
                    actionVersion = parts[5].toInt(),
                    scope = Codec.decodeSet(parts[6]),
                    attributedTo = Codec.decode(parts[7]),
                    parameters = Codec.decodeMap(parts[8]),
                    parametersDigest = Codec.decode(parts[9]),
                    attemptId = Codec.decode(parts[10]),
                    attemptNumber = parts[11].toInt(),
                    status = EffectStatus.valueOf(parts[12]),
                    retryAuthorization = EffectRetryAuthorization(
                        previousAttemptId = Codec.decode(parts[13]),
                        previousAttemptNumber = parts[14].toInt(),
                        attestationRef = Codec.decode(parts[15]),
                        attestationDigest = Codec.decode(parts[16]),
                        trustPolicyRef = Codec.decode(parts[17]),
                        authorizedAttemptId = Codec.decode(parts[18]),
                        authorizedAttemptNumber = parts[19].toInt(),
                        revalidatedAt = Codec.decode(parts[20])
                    )
                )
                else -> throw IllegalArgumentException("unsupported effect record shape")
            }
        }.getOrNull()
    }
}

/** Event-log transitions include reconciliation; direct API calls are narrower. */
/** X records are for direct RESERVED transitions only; UNKNOWN needs a trusted A attestation event. */
private fun legalEffectTransition(previous: EffectStatus, next: EffectStatus): Boolean = when (previous) {
    EffectStatus.RESERVED -> next == EffectStatus.COMPLETED || next == EffectStatus.UNKNOWN
    EffectStatus.UNKNOWN, EffectStatus.COMPLETED, EffectStatus.CONFIRMED_NOT_EXECUTED -> false
}

private fun legalDirectEffectTransition(previous: EffectStatus, next: EffectStatus): Boolean =
    previous == EffectStatus.RESERVED &&
        (next == EffectStatus.COMPLETED || next == EffectStatus.UNKNOWN)

/** A local store with the same legal-transition reducer as the journal store. */
class InMemoryRuntimeStore : RuntimeStore {
    private val runs = linkedMapOf<String, Run>()
    private val effects = linkedMapOf<String, StoredEffect>()
    private val usedAttemptIds = linkedMapOf<String, MutableSet<String>>()

    @Synchronized override fun saveRun(run: Run) { runs[run.id] = run }
    @Synchronized override fun loadRun(runId: String, catalog: ActionCatalog): Run? = runs[runId]

    override fun reserveEffects(
        invocations: List<CapabilityInvocation>,
        retryTrustVerifier: ReconciliationTrustVerifier?
    ): EffectReservation {
        if (invocations.map { it.effectId }.distinct().size != invocations.size) {
            return EffectReservation.CONFLICT
        }
        val incoming = invocations.map { StoredEffect.from(it, EffectStatus.RESERVED) }
        if (incoming.any { !it.hasValidIdentity() }) return EffectReservation.CONFLICT

        val snapshots = synchronized(this) {
            val previous = linkedMapOf<String, EffectRecord>()
            for (candidate in incoming) {
                val current = effects[candidate.effectId]
                if (current == null) {
                    if (candidate.attemptNumber != 1) return@synchronized null
                    if (usedAttemptIds[candidate.effectId]?.contains(candidate.attemptId) == true) {
                        return@synchronized null
                    }
                    continue
                }
                if (!current.compatibleWith(candidate)) return@synchronized null
                if (current.status != EffectStatus.CONFIRMED_NOT_EXECUTED) {
                    return@synchronized null
                }
                if (candidate.attemptNumber != current.attemptNumber + 1) return@synchronized null
                if (usedAttemptIds[candidate.effectId]?.contains(candidate.attemptId) == true) {
                    return@synchronized null
                }
                previous[candidate.effectId] = current.record()
            }
            previous
        } ?: run {
            return synchronized(this) {
                incoming.firstNotNullOfOrNull { candidate ->
                    val current = effects[candidate.effectId]
                    when {
                        current != null && current.status != EffectStatus.CONFIRMED_NOT_EXECUTED ->
                            EffectReservation.REPLAY_BLOCKED
                        current != null && !current.compatibleWith(candidate) ->
                            EffectReservation.CONFLICT
                        current != null && candidate.attemptNumber != current.attemptNumber + 1 ->
                            EffectReservation.CONFLICT
                        current == null && candidate.attemptNumber != 1 ->
                            EffectReservation.CONFLICT
                        else -> null
                    }
                } ?: EffectReservation.CONFLICT
            }
        }

        val retryAuthorizations = linkedMapOf<String, EffectRetryAuthorization>()
        for (candidate in incoming) {
            val previous = snapshots[candidate.effectId] ?: continue
            val (error, authorization) = prepareRetryAuthorization(
                previous,
                CapabilityInvocation(
                    runId = candidate.runId,
                    capabilityId = candidate.capabilityId,
                    actionId = candidate.actionId,
                    actionVersion = candidate.actionVersion,
                    effectId = candidate.effectId,
                    scope = candidate.scope,
                    attributedTo = candidate.attributedTo,
                    parameters = candidate.parameters,
                    attemptId = candidate.attemptId,
                    attemptNumber = candidate.attemptNumber
                ),
                retryTrustVerifier
            )
            if (error != null) return error
            retryAuthorizations[candidate.effectId] = requireNotNull(authorization)
        }

        return synchronized(this) {
            for (candidate in incoming) {
                val latest = effects[candidate.effectId]
                val snapshot = snapshots[candidate.effectId]
                if (snapshot == null) {
                    if (latest != null) return@synchronized EffectReservation.RETRY_AUTHORIZATION_STALE
                    if (candidate.attemptNumber != 1) return@synchronized EffectReservation.CONFLICT
                    if (usedAttemptIds[candidate.effectId]?.contains(candidate.attemptId) == true) {
                        return@synchronized EffectReservation.CONFLICT
                    }
                } else {
                    if (
                        latest == null ||
                        !sameIdentityAndAttempt(latest.record(), snapshot) ||
                        latest.reconciliationAttestation != snapshot.reconciliationAttestation ||
                        latest.status != EffectStatus.CONFIRMED_NOT_EXECUTED
                    ) {
                        return@synchronized EffectReservation.RETRY_AUTHORIZATION_STALE
                    }
                    val authorization = retryAuthorizations[candidate.effectId]
                        ?: return@synchronized EffectReservation.RECONCILIATION_REVALIDATION_REQUIRED
                    val next = candidate.copy(
                        reconciliationAttestation = latest.reconciliationAttestation
                    )
                    if (!validRetryAuthorization(authorization, latest, next)) {
                        return@synchronized EffectReservation.TRUST_REJECTED
                    }
                }
            }

            incoming.forEach { candidate ->
                val previous = effects[candidate.effectId]
                effects[candidate.effectId] = candidate.copy(
                    reconciliationAttestation = previous?.reconciliationAttestation,
                    retryAuthorization = retryAuthorizations[candidate.effectId]
                )
                usedAttemptIds.getOrPut(candidate.effectId) { linkedSetOf() }.add(candidate.attemptId)
            }
            EffectReservation.RESERVED
        }
    }

    @Synchronized override fun completeEffect(effectId: String): EffectTransitionResult =
        transition(effectId, EffectStatus.COMPLETED)

    @Synchronized override fun markEffectUnknown(effectId: String): EffectTransitionResult =
        transition(effectId, EffectStatus.UNKNOWN)

    private fun transition(effectId: String, next: EffectStatus): EffectTransitionResult {
        val current = effects[effectId] ?: return EffectTransitionResult.NOT_FOUND
        if (!legalDirectEffectTransition(current.status, next)) return EffectTransitionResult.INVALID_TRANSITION
        effects[effectId] = current.copy(status = next)
        return EffectTransitionResult.APPLIED
    }

    override fun reconcileEffect(
        effectId: String,
        attestation: EffectReconciliationAttestation,
        trustVerifier: ReconciliationTrustVerifier?
    ): EffectReconciliationResult {
        val snapshot = synchronized(this) {
            val current = effects[effectId] ?: return EffectReconciliationResult.NOT_FOUND
            if (current.status != EffectStatus.UNKNOWN) return EffectReconciliationResult.NOT_UNKNOWN
            current.record()
        }
        val (error, audit) = prepareReconciliation(attestation, snapshot, trustVerifier)
        if (error != null) return error
        val acceptedAudit = requireNotNull(audit)

        return synchronized(this) {
            val latest = effects[effectId] ?: return@synchronized EffectReconciliationResult.NOT_FOUND
            if (!sameIdentityAndAttempt(latest.record(), snapshot)) {
                return@synchronized EffectReconciliationResult.STALE_ATTEMPT
            }
            if (latest.status != EffectStatus.UNKNOWN) {
                return@synchronized EffectReconciliationResult.NOT_UNKNOWN
            }
            effects[effectId] = latest.copy(
                status = attestation.decision.toStatus(),
                reconciliationAttestation = acceptedAudit
            )
            if (attestation.decision == EffectReconciliationDecision.UNRESOLVED) {
                EffectReconciliationResult.UNRESOLVED
            } else {
                EffectReconciliationResult.RECONCILED
            }
        }
    }

    @Synchronized override fun currentEffect(effectId: String): EffectRecord? =
        effects[effectId]?.record()

    @Synchronized override fun unknownEffects(): List<EffectRecord> =
        effects.values.filter { it.status == EffectStatus.UNKNOWN }.map { it.record() }

    @Synchronized override fun recoverInterruptedEffects(): Int {
        val reserved = effects.values.filter { it.status == EffectStatus.RESERVED }
        reserved.forEach { effect -> effects[effect.effectId] = effect.copy(status = EffectStatus.UNKNOWN) }
        return reserved.size
    }
}

/**
 * Append-only journal. Any malformed record or unterminated tail fails closed:
 * callers cannot read, reserve, reconcile or append until the file is repaired.
 */
class JournalRuntimeStore(private val file: File) : RuntimeStore {
    private val sharedPathLock = pathLocks.computeIfAbsent(file.canonicalPath) { ReentrantLock(true) }

    init {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
    }

    private fun <T> withJournalLock(block: (FileChannel) -> T): T = sharedPathLock.withLock {
        FileChannel.open(
            file.toPath(),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        ).use { channel ->
            val fileLock = channel.lock()
            try {
                block(channel)
            } finally {
                fileLock.release()
            }
        }
    }

    override fun saveRun(run: Run) = withJournalLock { channel ->
        replay(channel) // Validate the existing journal before appending anything.
        append(channel, RunRecord.encode(run))
    }

    override fun loadRun(runId: String, catalog: ActionCatalog): Run? =
        withJournalLock { channel -> replay(channel).runs[runId]?.toRun(catalog) }

    override fun reserveEffects(
        invocations: List<CapabilityInvocation>,
        retryTrustVerifier: ReconciliationTrustVerifier?
    ): EffectReservation {
        if (invocations.map { it.effectId }.distinct().size != invocations.size) {
            return EffectReservation.CONFLICT
        }
        val incoming = invocations.map { StoredEffect.from(it, EffectStatus.RESERVED) }
        if (incoming.any { !it.hasValidIdentity() }) return EffectReservation.CONFLICT

        val snapshotResult = withJournalLock { channel ->
            val state = replay(channel)
            val previous = linkedMapOf<String, EffectRecord>()
            var failure: EffectReservation? = null
            for (candidate in incoming) {
                val current = state.effects[candidate.effectId]
                when {
                    current == null && candidate.attemptNumber != 1 ->
                        failure = EffectReservation.CONFLICT
                    current == null && state.usedAttemptIds[candidate.effectId]?.contains(candidate.attemptId) == true ->
                        failure = EffectReservation.CONFLICT
                    current != null && !current.compatibleWith(candidate) ->
                        failure = EffectReservation.CONFLICT
                    current != null && current.status != EffectStatus.CONFIRMED_NOT_EXECUTED ->
                        failure = EffectReservation.REPLAY_BLOCKED
                    current != null && candidate.attemptNumber != current.attemptNumber + 1 ->
                        failure = EffectReservation.CONFLICT
                    current != null && state.usedAttemptIds[candidate.effectId]?.contains(candidate.attemptId) == true ->
                        failure = EffectReservation.CONFLICT
                    current != null -> previous[candidate.effectId] = current.record()
                }
                if (failure != null) break
            }
            failure to previous
        }
        snapshotResult.first?.let { return it }
        val snapshots = snapshotResult.second

        val retryAuthorizations = linkedMapOf<String, EffectRetryAuthorization>()
        for (candidate in incoming) {
            val previous = snapshots[candidate.effectId] ?: continue
            val invocation = CapabilityInvocation(
                runId = candidate.runId,
                capabilityId = candidate.capabilityId,
                actionId = candidate.actionId,
                actionVersion = candidate.actionVersion,
                effectId = candidate.effectId,
                scope = candidate.scope,
                attributedTo = candidate.attributedTo,
                parameters = candidate.parameters,
                attemptId = candidate.attemptId,
                attemptNumber = candidate.attemptNumber
            )
            val (error, authorization) = prepareRetryAuthorization(
                previous,
                invocation,
                retryTrustVerifier
            )
            if (error != null) return error
            retryAuthorizations[candidate.effectId] = requireNotNull(authorization)
        }

        return withJournalLock { channel ->
            val state = replay(channel)
            for (candidate in incoming) {
                val latest = state.effects[candidate.effectId]
                val snapshot = snapshots[candidate.effectId]
                if (snapshot == null) {
                    if (latest != null) return@withJournalLock EffectReservation.RETRY_AUTHORIZATION_STALE
                    if (candidate.attemptNumber != 1) return@withJournalLock EffectReservation.CONFLICT
                    if (state.usedAttemptIds[candidate.effectId]?.contains(candidate.attemptId) == true) {
                        return@withJournalLock EffectReservation.CONFLICT
                    }
                } else {
                    if (
                        latest == null ||
                        !sameIdentityAndAttempt(latest.record(), snapshot) ||
                        latest.reconciliationAttestation != snapshot.reconciliationAttestation ||
                        latest.status != EffectStatus.CONFIRMED_NOT_EXECUTED
                    ) {
                        return@withJournalLock EffectReservation.RETRY_AUTHORIZATION_STALE
                    }
                    val authorization = retryAuthorizations[candidate.effectId]
                        ?: return@withJournalLock EffectReservation.RECONCILIATION_REVALIDATION_REQUIRED
                    val next = candidate.copy(
                        reconciliationAttestation = latest.reconciliationAttestation
                    )
                    if (!validRetryAuthorization(authorization, latest, next)) {
                        return@withJournalLock EffectReservation.TRUST_REJECTED
                    }
                }
            }

            incoming.forEach { candidate ->
                val previous = state.effects[candidate.effectId]
                append(
                    channel,
                    candidate.copy(
                        reconciliationAttestation = previous?.reconciliationAttestation,
                        retryAuthorization = retryAuthorizations[candidate.effectId]
                    ).encode()
                )
            }
            EffectReservation.RESERVED
        }
    }

    override fun completeEffect(effectId: String): EffectTransitionResult =
        withJournalLock { channel -> transition(channel, effectId, EffectStatus.COMPLETED) }

    override fun markEffectUnknown(effectId: String): EffectTransitionResult =
        withJournalLock { channel -> transition(channel, effectId, EffectStatus.UNKNOWN) }

    private fun transition(
        channel: FileChannel,
        effectId: String,
        next: EffectStatus
    ): EffectTransitionResult {
        val current = replay(channel).effects[effectId] ?: return EffectTransitionResult.NOT_FOUND
        if (!legalDirectEffectTransition(current.status, next)) return EffectTransitionResult.INVALID_TRANSITION
        append(channel, "X|${Codec.encode(effectId)}|${next.name}")
        return EffectTransitionResult.APPLIED
    }

    override fun reconcileEffect(
        effectId: String,
        attestation: EffectReconciliationAttestation,
        trustVerifier: ReconciliationTrustVerifier?
    ): EffectReconciliationResult {
        val snapshotResult = withJournalLock { channel ->
            val current = replay(channel).effects[effectId]
            when {
                current == null -> EffectReconciliationResult.NOT_FOUND to null
                current.status != EffectStatus.UNKNOWN -> EffectReconciliationResult.NOT_UNKNOWN to null
                else -> null to current.record().copy(
                    scope = current.scope.toSet(),
                    parameters = current.parameters.toMap()
                )
            }
        }
        snapshotResult.first?.let { return it }
        val snapshot = requireNotNull(snapshotResult.second)
        val (error, audit) = prepareReconciliation(attestation, snapshot, trustVerifier)
        if (error != null) return error
        val acceptedAudit = requireNotNull(audit)

        return withJournalLock { channel ->
            val latest = replay(channel).effects[effectId]
                ?: return@withJournalLock EffectReconciliationResult.NOT_FOUND
            if (!sameIdentityAndAttempt(latest.record(), snapshot)) {
                return@withJournalLock EffectReconciliationResult.STALE_ATTEMPT
            }
            if (latest.status != EffectStatus.UNKNOWN) {
                return@withJournalLock EffectReconciliationResult.NOT_UNKNOWN
            }
            append(channel, encodeReconciliationAudit(effectId, acceptedAudit))
            if (attestation.decision == EffectReconciliationDecision.UNRESOLVED) {
                EffectReconciliationResult.UNRESOLVED
            } else {
                EffectReconciliationResult.RECONCILED
            }
        }
    }

    private fun encodeReconciliationAudit(
        effectId: String,
        audit: EffectReconciliationAudit
    ): String = listOf(
        "A",
        Codec.encode(effectId),
        Codec.encode(audit.attemptId),
        audit.attemptNumber.toString(),
        audit.decision.name,
        Codec.encode(audit.attestationRef),
        Codec.encode(audit.attestationDigest),
        Codec.encode(audit.trustPolicyRef),
        Codec.encode(audit.authorityRef),
        Codec.encode(audit.observationRef),
        Codec.encode(audit.evidenceRef),
        Codec.encode(audit.evidenceDigest),
        Codec.encode(audit.verifierRef),
        Codec.encode(audit.verifierVersion),
        Codec.encode(audit.principalKind),
        Codec.encode(audit.principalRef)
    ).joinToString("|")

    override fun currentEffect(effectId: String): EffectRecord? =
        withJournalLock { channel -> replay(channel).effects[effectId]?.record() }

    override fun unknownEffects(): List<EffectRecord> = withJournalLock { channel ->
        replay(channel).effects.values.filter { it.status == EffectStatus.UNKNOWN }.map { it.record() }
    }

    override fun recoverInterruptedEffects(): Int = withJournalLock { channel ->
        val reserved = replay(channel).effects.values.filter { it.status == EffectStatus.RESERVED }
        reserved.forEach { append(channel, "X|${Codec.encode(it.effectId)}|UNKNOWN") }
        reserved.size
    }

    private fun append(channel: FileChannel, record: String) {
        val payload = (record + "\n").toByteArray(StandardCharsets.UTF_8)
        channel.position(channel.size())
        writeFully(channel, ByteBuffer.wrap(payload))
        channel.force(true)
    }

    private fun replay(channel: FileChannel): State {
        val fileSize = channel.size()
        if (fileSize > Int.MAX_VALUE.toLong()) {
            throw IllegalStateException("journal exceeds supported in-memory replay size")
        }
        channel.position(0L)
        val buffer = ByteBuffer.allocate(fileSize.toInt())
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer)
            if (count <= 0) break
        }
        if (buffer.position() != buffer.capacity()) {
            throw IllegalStateException("journal read was incomplete; refusing state reconstruction")
        }
        val bytes = buffer.array()
        if (bytes.isNotEmpty() && bytes.last() != '\n'.code.toByte()) {
            throw IllegalStateException("journal has an unterminated trailing record; repair required")
        }

        val runs = linkedMapOf<String, RunRecord>()
        val effects = linkedMapOf<String, StoredEffect>()
        val usedAttemptIds = linkedMapOf<String, MutableSet<String>>()
        val text = String(bytes, StandardCharsets.UTF_8)
        val lines = text.split('\n')
        val recordLines = if (lines.lastOrNull() == "") lines.dropLast(1) else lines

        recordLines.forEachIndexed { index, line ->
            if (line.isEmpty()) {
                throw IllegalStateException("journal contains an empty record at line ${index + 1}")
            }
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "R" -> {
                    val run = RunRecord.decode(parts)
                        ?: throw IllegalStateException("malformed run record at line ${index + 1}")
                    runs[run.id] = run
                }
                "E", "E2", "E3", "E4" -> {
                    val decoded = StoredEffect.decode(parts, "legacy-attempt-${index + 1}")
                        ?: throw IllegalStateException("malformed effect record at line ${index + 1}")
                    val previous = effects[decoded.effectId]
                    val effect = (if (parts[0] == "E3" || parts[0] == "E4") decoded else decoded.copy(
                        attemptNumber = (previous?.attemptNumber ?: 0) + 1
                    )).copy(reconciliationAttestation = previous?.reconciliationAttestation)
                    if (!effect.hasValidIdentity() || effect.status != EffectStatus.RESERVED) {
                        throw IllegalStateException("invalid effect reservation at line ${index + 1}")
                    }

                    val seenAttempts = usedAttemptIds.getOrPut(effect.effectId) { linkedSetOf() }
                    if (effect.attemptId in seenAttempts) {
                        throw IllegalStateException("reused effect attempt ID at line ${index + 1}")
                    }
                    if (previous == null) {
                        if (effect.attemptNumber != 1 || effect.retryAuthorization != null) {
                            throw IllegalStateException("first effect attempt must be number 1 and cannot carry retry authorization at line ${index + 1}")
                        }
                    } else {
                        if (
                            !previous.compatibleWith(effect) ||
                            previous.status != EffectStatus.CONFIRMED_NOT_EXECUTED ||
                            effect.attemptNumber != previous.attemptNumber + 1
                        ) {
                            throw IllegalStateException("illegal effect reservation at line ${index + 1}")
                        }
                        val authorization = effect.retryAuthorization
                        if (parts[0] != "E4" || authorization == null ||
                            !validRetryAuthorization(authorization, previous, effect)
                        ) {
                            throw IllegalStateException("retry lacks valid replay-time trust authorization at line ${index + 1}")
                        }
                    }
                    if ((parts[0] == "E4") != (effect.retryAuthorization != null)) {
                        throw IllegalStateException("retry authorization record shape mismatch at line ${index + 1}")
                    }
                    seenAttempts.add(effect.attemptId)
                    effects[effect.effectId] = effect
                }
                "A" -> {
                    if (parts.size != 16) {
                        throw IllegalStateException("malformed reconciliation attestation event at line ${index + 1}")
                    }
                    val effectId = runCatching { Codec.decode(parts[1]) }.getOrElse {
                        throw IllegalStateException("invalid reconciliation effect ID at line ${index + 1}", it)
                    }
                    val attemptId = runCatching { Codec.decode(parts[2]) }.getOrElse {
                        throw IllegalStateException("invalid reconciliation attempt ID at line ${index + 1}", it)
                    }
                    val attemptNumber = parts[3].toIntOrNull()
                        ?: throw IllegalStateException("invalid reconciliation attempt number at line ${index + 1}")
                    if (attemptNumber < 1) {
                        throw IllegalStateException("invalid reconciliation attempt number at line ${index + 1}")
                    }
                    val decision = runCatching { EffectReconciliationDecision.valueOf(parts[4]) }.getOrElse {
                        throw IllegalStateException("invalid reconciliation decision at line ${index + 1}", it)
                    }
                    val audit = EffectReconciliationAudit(
                        attestationRef = Codec.decode(parts[5]),
                        attestationDigest = Codec.decode(parts[6]),
                        trustPolicyRef = Codec.decode(parts[7]),
                        attemptId = attemptId,
                        attemptNumber = attemptNumber,
                        decision = decision,
                        authorityRef = Codec.decode(parts[8]),
                        observationRef = Codec.decode(parts[9]),
                        evidenceRef = Codec.decode(parts[10]),
                        evidenceDigest = Codec.decode(parts[11]),
                        verifierRef = Codec.decode(parts[12]),
                        verifierVersion = Codec.decode(parts[13]),
                        principalKind = Codec.decode(parts[14]),
                        principalRef = Codec.decode(parts[15])
                    )
                    val previous = effects[effectId]
                        ?: throw IllegalStateException("attestation references unknown effect at line ${index + 1}")
                    if (
                        previous.status != EffectStatus.UNKNOWN ||
                        previous.attemptId != attemptId ||
                        previous.attemptNumber != attemptNumber
                    ) {
                        throw IllegalStateException("stale or illegal reconciliation attestation at line ${index + 1}")
                    }
                    if (
                        audit.attestationRef.isBlank() ||
                        !Regex("^sha256:[0-9a-f]{64}$").matches(audit.attestationDigest) ||
                        audit.trustPolicyRef.isBlank() ||
                        audit.authorityRef.isBlank() ||
                        audit.observationRef.isBlank() ||
                        audit.evidenceRef.isBlank() ||
                        !Regex("^sha256:[0-9a-f]{64}$").matches(audit.evidenceDigest) ||
                        audit.verifierRef.isBlank() ||
                        audit.verifierVersion.isBlank() ||
                        audit.principalKind !in setOf("human", "service") ||
                        audit.principalRef.isBlank()
                    ) {
                        throw IllegalStateException("invalid reconciliation audit data at line ${index + 1}")
                    }
                    effects[effectId] = previous.copy(
                        status = decision.toStatus(),
                        reconciliationAttestation = audit
                    )
                }
                "X" -> {
                    if (parts.size != 3) {
                        throw IllegalStateException("malformed effect transition at line ${index + 1}")
                    }
                    val effectId = runCatching { Codec.decode(parts[1]) }.getOrElse {
                        throw IllegalStateException("invalid effect ID at line ${index + 1}", it)
                    }
                    val next = runCatching { EffectStatus.valueOf(parts[2]) }.getOrElse {
                        throw IllegalStateException("invalid effect status at line ${index + 1}", it)
                    }
                    val previous = effects[effectId]
                        ?: throw IllegalStateException("transition references unknown effect at line ${index + 1}")
                    if (!legalEffectTransition(previous.status, next)) {
                        throw IllegalStateException("illegal effect transition at line ${index + 1}")
                    }
                    effects[effectId] = previous.copy(status = next)
                }
                else -> throw IllegalStateException("unknown journal record type at line ${index + 1}")
            }
        }
        return State(runs, effects, usedAttemptIds)
    }

    private data class State(
        val runs: MutableMap<String, RunRecord>,
        val effects: MutableMap<String, StoredEffect>,
        val usedAttemptIds: MutableMap<String, MutableSet<String>>
    )

    companion object {
        private val pathLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}

/** Loops until the entire record is written; a zero-progress write fails closed. */
internal fun writeFully(channel: WritableByteChannel, buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
        val written = channel.write(buffer)
        if (written <= 0) throw IOException("journal channel made no write progress")
    }
}

private fun EffectReconciliationDecision.toStatus(): EffectStatus = when (this) {
    EffectReconciliationDecision.CONFIRMED_COMPLETED -> EffectStatus.COMPLETED
    EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED -> EffectStatus.CONFIRMED_NOT_EXECUTED
    EffectReconciliationDecision.UNRESOLVED -> EffectStatus.UNKNOWN
}

private data class RunRecord(
    val id: String,
    val source: ActivationSource,
    val actionId: String,
    val actionVersion: Int,
    val identity: String,
    val status: RunStatus,
    val input: Map<String, String>,
    val output: Map<String, String>,
    val denialReason: String?,
    val approvalId: String?,
    val evidence: Evidence?
) {
    fun toRun(catalog: ActionCatalog): Run? = catalog.get(actionId)?.let { action ->
        Run(id, ActivationRequest(source, actionId, input, identity), status, action.copy(version = actionVersion), output, evidence, denialReason, approvalId)
    }

    companion object {
        fun encode(run: Run): String {
            val e = run.evidence
            return listOf(
                "R", Codec.encode(run.id), run.activation.source.name,
                Codec.encode(run.activation.actionId), run.action.version.toString(),
                Codec.encode(run.activation.identity), run.status.name,
                Codec.encodeMap(run.activation.input), Codec.encodeMap(run.output),
                Codec.encodeNullable(run.denialReason), Codec.encodeNullable(run.approvalId),
                if (e == null) "0" else "1",
                if (e == null) "" else Codec.encode(e.authorizedBy ?: ""),
                if (e == null) "" else Codec.encodeInvocations(e.capabilityInvocations),
                if (e == null) "" else Codec.encodeObservations(e.observations),
                if (e == null) "" else e.verification.passed.toString(),
                if (e == null) "" else Codec.encode(e.verification.reason)
            ).joinToString("|")
        }

        fun decode(parts: List<String>): RunRecord? {
            if (parts[0] != "R" || (parts.size != 16 && parts.size != 17)) return null
            return runCatching {
                val legacy = parts.size == 16
                val approvalId = if (legacy) null else Codec.decodeNullable(parts[10])
                val evidenceFlag = parts[if (legacy) 10 else 11]
                val evidence = if (evidenceFlag == "1") {
                    val base = if (legacy) 11 else 12
                    Evidence(
                        runId = Codec.decode(parts[1]),
                        actionId = Codec.decode(parts[3]),
                        actionVersion = parts[4].toInt(),
                        activationSource = ActivationSource.valueOf(parts[2]),
                        authorizedBy = Codec.decode(parts[base]).ifBlank { null },
                        capabilityInvocations = Codec.decodeInvocations(parts[base + 1]),
                        observations = Codec.decodeObservations(parts[base + 2]),
                        verification = Verification(
                            parts[base + 3].toBooleanStrict(),
                            Codec.decode(parts[base + 4])
                        )
                    )
                } else null
                RunRecord(
                    Codec.decode(parts[1]),
                    ActivationSource.valueOf(parts[2]),
                    Codec.decode(parts[3]),
                    parts[4].toInt(),
                    Codec.decode(parts[5]),
                    RunStatus.valueOf(parts[6]),
                    Codec.decodeMap(parts[7]),
                    Codec.decodeMap(parts[8]),
                    Codec.decodeNullable(parts[9]),
                    approvalId,
                    evidence
                )
            }.getOrNull()
        }
    }
}

private object Codec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    fun decode(value: String): String =
        if (value.isBlank()) "" else String(decoder.decode(value), StandardCharsets.UTF_8)

    fun encodeNullable(value: String?): String = value?.let { "1:${encode(it)}" } ?: "0"

    fun decodeNullable(value: String): String? =
        if (value == "0" || value.isBlank()) null else decode(value.removePrefix("1:"))

    fun encodeSet(value: Set<String>): String =
        value.toList().sorted().joinToString(",") { encode(it) }

    fun decodeSet(value: String): Set<String> =
        if (value.isBlank()) emptySet() else value.split(',').map(::decode).toSet()

    fun encodeMap(value: Map<String, String>): String =
        value.toSortedMap().entries.joinToString(",") { "${encode(it.key)}.${encode(it.value)}" }

    fun decodeMap(value: String): Map<String, String> =
        if (value.isBlank()) emptyMap() else value.split(',').associate {
            val separator = it.indexOf('.')
            require(separator > 0)
            decode(it.substring(0, separator)) to decode(it.substring(separator + 1))
        }

    fun encodeInvocations(value: List<CapabilityInvocation>): String =
        value.joinToString("~") { invocation ->
            listOf(
                encode(invocation.id),
                encode(invocation.runId),
                encode(invocation.capabilityId),
                encode(invocation.actionId),
                invocation.actionVersion.toString(),
                encode(invocation.effectId),
                encodeSet(invocation.scope),
                encode(invocation.attributedTo),
                encodeMap(invocation.parameters),
                encode(invocation.attemptId),
                invocation.attemptNumber.toString()
            ).joinToString("^")
        }

    fun decodeInvocations(value: String): List<CapabilityInvocation> =
        if (value.isBlank()) emptyList() else value.split('~').map { raw ->
            val parts = raw.split('^')
            require(parts.size == 9 || parts.size == 10 || parts.size == 11)
            CapabilityInvocation(
                id = decode(parts[0]),
                runId = decode(parts[1]),
                capabilityId = decode(parts[2]),
                actionId = decode(parts[3]),
                actionVersion = parts[4].toInt(),
                effectId = decode(parts[5]),
                scope = decodeSet(parts[6]),
                attributedTo = decode(parts[7]),
                parameters = decodeMap(parts[8]),
                attemptId = if (parts.size >= 10) decode(parts[9]) else decode(parts[0]),
                attemptNumber = if (parts.size == 11) parts[10].toInt() else 1
            )
        }

    fun encodeObservations(value: List<Observation>): String =
        value.joinToString("~") { "${encode(it.key)}^${encode(it.value)}" }

    fun decodeObservations(value: String): List<Observation> =
        if (value.isBlank()) emptyList() else value.split('~').map {
            val parts = it.split('^')
            require(parts.size == 2)
            Observation(decode(parts[0]), decode(parts[1]))
        }
}
