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

/** Durable runtime boundary. Implementations must serialize state before returning. */
interface RuntimeStore {
    fun saveRun(run: Run)
    fun loadRun(runId: String, catalog: ActionCatalog): Run?
    fun reserveEffects(invocations: List<CapabilityInvocation>): EffectReservation
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

enum class EffectReservation { RESERVED, REPLAY_BLOCKED, CONFLICT }
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
    val reconciliationAttestation: EffectReconciliationAudit? = null
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

internal fun effectReconciliationAttestationDigest(
    attestation: EffectReconciliationAttestation
): String {
    val fields = mutableListOf<String>()
    fun add(value: String) { fields.add(value) }

    add(attestation.schema)
    val identity = attestation.effectIdentity
    add(identity.schema)
    add(identity.effectId)
    add(identity.runId)
    add(identity.capabilityId)
    add(identity.actionId)
    add(identity.actionVersion.toString())
    add(identity.scope.size.toString())
    identity.scope.sorted().forEach(::add)
    add(identity.attributedTo)
    add(identity.parametersDigest)
    add(attestation.attemptId)
    add(attestation.attemptNumber.toString())
    add(attestation.decision.name)
    add(attestation.decidedAt)
    add(attestation.authority.kind)
    add(attestation.authority.ref)
    add(attestation.authority.observationRef)
    add(attestation.authority.queriedAt)
    add(attestation.authority.observedAt)
    add(attestation.evidence.artifactRef)
    add(attestation.evidence.digest)
    add(attestation.verifier.ref)
    add(attestation.verifier.version)
    add(attestation.verifier.result)
    add(attestation.verifier.verifiedAt)
    add(attestation.accountablePrincipal.kind)
    add(attestation.accountablePrincipal.ref)
    add(attestation.authentication.scheme)
    add(attestation.authentication.keyRef)
    add(attestation.authentication.signedDigest)
    add(attestation.authentication.signature)
    add(attestation.reason)

    val canonical = buildString {
        fields.forEach { value ->
            val byteLength = value.toByteArray(StandardCharsets.UTF_8).size
            append(byteLength).append(':').append(value)
        }
    }.toByteArray(StandardCharsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical)
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
    val reconciliationAttestation: EffectReconciliationAudit? = null
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
        reconciliationAttestation
    )

    /** E3 adds the monotonic attempt number; older E/E2 events remain readable. */
    fun encode(): String = listOf(
        "E3",
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
    ).joinToString("|")

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

    @Synchronized override fun reserveEffects(invocations: List<CapabilityInvocation>): EffectReservation {
        if (invocations.map { it.effectId }.distinct().size != invocations.size) return EffectReservation.CONFLICT
        val incoming = invocations.map { StoredEffect.from(it, EffectStatus.RESERVED) }
        if (incoming.any { !it.hasValidIdentity() }) return EffectReservation.CONFLICT

        for (candidate in incoming) {
            val current = effects[candidate.effectId]
            if (current == null) {
                if (candidate.attemptNumber != 1) return EffectReservation.CONFLICT
                if (usedAttemptIds[candidate.effectId]?.contains(candidate.attemptId) == true) {
                    return EffectReservation.CONFLICT
                }
                continue
            }
            if (!current.compatibleWith(candidate)) return EffectReservation.CONFLICT
            if (current.status != EffectStatus.CONFIRMED_NOT_EXECUTED) return EffectReservation.REPLAY_BLOCKED
            if (candidate.attemptNumber != current.attemptNumber + 1) return EffectReservation.CONFLICT
            if (usedAttemptIds[candidate.effectId]?.contains(candidate.attemptId) == true) {
                return EffectReservation.CONFLICT
            }
        }

        incoming.forEach {
            effects[it.effectId] = it
            usedAttemptIds.getOrPut(it.effectId) { linkedSetOf() }.add(it.attemptId)
        }
        return EffectReservation.RESERVED
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
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
    }

    override fun saveRun(run: Run) = synchronized(lock) {
        replay() // Validate the existing journal before appending anything.
        append(RunRecord.encode(run))
    }

    override fun loadRun(runId: String, catalog: ActionCatalog): Run? =
        synchronized(lock) { replay().runs[runId]?.toRun(catalog) }

    override fun reserveEffects(invocations: List<CapabilityInvocation>): EffectReservation = synchronized(lock) {
        if (invocations.map { it.effectId }.distinct().size != invocations.size) {
            return@synchronized EffectReservation.CONFLICT
        }

        val state = replay()
        val incoming = invocations.map { StoredEffect.from(it, EffectStatus.RESERVED) }
        if (incoming.any { !it.hasValidIdentity() }) return@synchronized EffectReservation.CONFLICT

        for (candidate in incoming) {
            val current = state.effects[candidate.effectId]
            if (current == null) {
                if (candidate.attemptNumber != 1) return@synchronized EffectReservation.CONFLICT
                if (state.usedAttemptIds[candidate.effectId]?.contains(candidate.attemptId) == true) {
                    return@synchronized EffectReservation.CONFLICT
                }
                continue
            }
            if (!current.compatibleWith(candidate)) return@synchronized EffectReservation.CONFLICT
            if (current.status != EffectStatus.CONFIRMED_NOT_EXECUTED) {
                return@synchronized EffectReservation.REPLAY_BLOCKED
            }
            if (candidate.attemptNumber != current.attemptNumber + 1) {
                return@synchronized EffectReservation.CONFLICT
            }
            if (state.usedAttemptIds[candidate.effectId]?.contains(candidate.attemptId) == true) {
                return@synchronized EffectReservation.CONFLICT
            }
        }

        incoming.forEach { append(it.encode()) }
        EffectReservation.RESERVED
    }

    override fun completeEffect(effectId: String): EffectTransitionResult = synchronized(lock) {
        transition(effectId, EffectStatus.COMPLETED)
    }

    override fun markEffectUnknown(effectId: String): EffectTransitionResult = synchronized(lock) {
        transition(effectId, EffectStatus.UNKNOWN)
    }

    private fun transition(effectId: String, next: EffectStatus): EffectTransitionResult {
        val current = replay().effects[effectId] ?: return EffectTransitionResult.NOT_FOUND
        if (!legalDirectEffectTransition(current.status, next)) return EffectTransitionResult.INVALID_TRANSITION
        append("X|${Codec.encode(effectId)}|${next.name}")
        return EffectTransitionResult.APPLIED
    }

    override fun reconcileEffect(
        effectId: String,
        attestation: EffectReconciliationAttestation,
        trustVerifier: ReconciliationTrustVerifier?
    ): EffectReconciliationResult {
        val snapshot = synchronized(lock) {
            val current = replay().effects[effectId] ?: return EffectReconciliationResult.NOT_FOUND
            if (current.status != EffectStatus.UNKNOWN) return EffectReconciliationResult.NOT_UNKNOWN
            current.record()
        }
        val (error, audit) = prepareReconciliation(attestation, snapshot, trustVerifier)
        if (error != null) return error
        val acceptedAudit = requireNotNull(audit)

        return synchronized(lock) {
            val latest = replay().effects[effectId]
                ?: return@synchronized EffectReconciliationResult.NOT_FOUND
            if (!sameIdentityAndAttempt(latest.record(), snapshot)) {
                return@synchronized EffectReconciliationResult.STALE_ATTEMPT
            }
            if (latest.status != EffectStatus.UNKNOWN) {
                return@synchronized EffectReconciliationResult.NOT_UNKNOWN
            }
            append(encodeReconciliationAudit(effectId, acceptedAudit))
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

    override fun currentEffect(effectId: String): EffectRecord? = synchronized(lock) {
        replay().effects[effectId]?.record()
    }

    override fun unknownEffects(): List<EffectRecord> = synchronized(lock) {
        replay().effects.values.filter { it.status == EffectStatus.UNKNOWN }.map { it.record() }
    }

    override fun recoverInterruptedEffects(): Int = synchronized(lock) {
        val reserved = replay().effects.values.filter { it.status == EffectStatus.RESERVED }
        reserved.forEach { append("X|${Codec.encode(it.effectId)}|UNKNOWN") }
        reserved.size
    }

    private fun append(record: String) {
        val payload = (record + "\n").toByteArray(StandardCharsets.UTF_8)
        FileChannel.open(
            file.toPath(),
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.APPEND
        ).use { channel ->
            writeFully(channel, ByteBuffer.wrap(payload))
            channel.force(true)
        }
    }

    private fun replay(): State {
        val bytes = file.readBytes()
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
                "E", "E2", "E3" -> {
                    val decoded = StoredEffect.decode(parts, "legacy-attempt-${index + 1}")
                        ?: throw IllegalStateException("malformed effect record at line ${index + 1}")
                    val previous = effects[decoded.effectId]
                    val effect = (if (parts[0] == "E3") decoded else decoded.copy(
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
                        if (effect.attemptNumber != 1) {
                            throw IllegalStateException("first effect attempt must be number 1 at line ${index + 1}")
                        }
                    } else if (
                        !previous.compatibleWith(effect) ||
                        previous.status != EffectStatus.CONFIRMED_NOT_EXECUTED ||
                        effect.attemptNumber != previous.attemptNumber + 1
                    ) {
                        throw IllegalStateException("illegal effect reservation at line ${index + 1}")
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
