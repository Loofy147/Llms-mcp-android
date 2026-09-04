package com.hicham.llmchat.runtime

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Durable runtime boundary. Implementations must serialize state before returning. */
interface RuntimeStore {
    fun saveRun(run: Run)
    fun loadRun(runId: String, catalog: ActionCatalog): Run?
    fun reserveEffects(invocations: List<CapabilityInvocation>): EffectReservation
    fun completeEffect(effectId: String)
    fun markEffectUnknown(effectId: String)
    fun reconcileEffect(effectId: String, decision: EffectReconciliationDecision): EffectReconciliationResult
    fun unknownEffects(): List<EffectRecord>
    /** Convert effects that were reserved before an interrupted process into explicitly reconcilable UNKNOWN state. */
    fun recoverInterruptedEffects(): Int
}

enum class EffectReservation { RESERVED, REPLAY_BLOCKED, CONFLICT }
enum class EffectReconciliationDecision { CONFIRMED_COMPLETED, CONFIRMED_NOT_EXECUTED }
enum class EffectReconciliationResult { RECONCILED, NOT_FOUND, NOT_UNKNOWN }
enum class EffectStatus { RESERVED, COMPLETED, UNKNOWN, CONFIRMED_NOT_EXECUTED }

data class EffectRecord(
    val effectId: String,
    val runId: String,
    val capabilityId: String,
    val actionId: String,
    val actionVersion: Int,
    val scope: Set<String>,
    val attributedTo: String,
    val parameters: Map<String, String>,
    val status: EffectStatus
)

private data class StoredEffect(
    val effectId: String, val runId: String, val capabilityId: String, val actionId: String,
    val actionVersion: Int, val scope: Set<String>, val attributedTo: String,
    val parameters: Map<String, String>, val status: EffectStatus
) {
    fun compatibleWith(other: StoredEffect): Boolean = effectId == other.effectId && capabilityId == other.capabilityId && actionId == other.actionId &&
        actionVersion == other.actionVersion && scope == other.scope && attributedTo == other.attributedTo && parameters == other.parameters
    fun record() = EffectRecord(effectId, runId, capabilityId, actionId, actionVersion, scope, attributedTo, parameters, status)
    fun encode(): String = listOf("E", Codec.encode(effectId), Codec.encode(runId), Codec.encode(capabilityId), Codec.encode(actionId), actionVersion.toString(), Codec.encodeSet(scope), Codec.encode(attributedTo), Codec.encodeMap(parameters), status.name).joinToString("|")
    companion object {
        fun from(invocation: CapabilityInvocation, status: EffectStatus) = StoredEffect(invocation.effectId, invocation.runId, invocation.capabilityId, invocation.actionId, invocation.actionVersion, invocation.scope, invocation.attributedTo, invocation.parameters, status)
        fun decode(parts: List<String>): StoredEffect? = if (parts.size != 10 || parts[0] != "E") null else runCatching {
            StoredEffect(Codec.decode(parts[1]), Codec.decode(parts[2]), Codec.decode(parts[3]), Codec.decode(parts[4]), parts[5].toInt(), Codec.decodeSet(parts[6]), Codec.decode(parts[7]), Codec.decodeMap(parts[8]), EffectStatus.valueOf(parts[9]))
        }.getOrNull()
    }
}

class InMemoryRuntimeStore : RuntimeStore {
    private val runs = linkedMapOf<String, Run>()
    private val effects = linkedMapOf<String, StoredEffect>()
    @Synchronized override fun saveRun(run: Run) { runs[run.id] = run }
    @Synchronized override fun loadRun(runId: String, catalog: ActionCatalog): Run? = runs[runId]
    @Synchronized override fun reserveEffects(invocations: List<CapabilityInvocation>): EffectReservation {
        if (invocations.map { it.effectId }.distinct().size != invocations.size) return EffectReservation.CONFLICT
        val incoming = invocations.map { StoredEffect.from(it, EffectStatus.RESERVED) }
        if (incoming.any { e -> effects[e.effectId]?.let { !it.compatibleWith(e) } == true }) return EffectReservation.CONFLICT
        if (incoming.any { effects[it.effectId]?.status != EffectStatus.CONFIRMED_NOT_EXECUTED && effects.containsKey(it.effectId) }) return EffectReservation.REPLAY_BLOCKED
        incoming.forEach { effects[it.effectId] = it }
        return EffectReservation.RESERVED
    }
    @Synchronized override fun completeEffect(effectId: String) { effects[effectId]?.let { effects[effectId] = it.copy(status = EffectStatus.COMPLETED) } }
    @Synchronized override fun markEffectUnknown(effectId: String) { effects[effectId]?.let { effects[effectId] = it.copy(status = EffectStatus.UNKNOWN) } }
    @Synchronized override fun reconcileEffect(effectId: String, decision: EffectReconciliationDecision): EffectReconciliationResult {
        val current = effects[effectId] ?: return EffectReconciliationResult.NOT_FOUND
        if (current.status != EffectStatus.UNKNOWN) return EffectReconciliationResult.NOT_UNKNOWN
        effects[effectId] = current.copy(status = decision.toStatus())
        return EffectReconciliationResult.RECONCILED
    }
    @Synchronized override fun unknownEffects(): List<EffectRecord> = effects.values.filter { it.status == EffectStatus.UNKNOWN }.map { it.record() }
    @Synchronized override fun recoverInterruptedEffects(): Int {
        val reserved = effects.values.filter { it.status == EffectStatus.RESERVED }
        reserved.forEach { effect -> effects[effect.effectId] = effect.copy(status = EffectStatus.UNKNOWN) }
        return reserved.size
    }
}

/** Append-only journal; malformed/torn final records are ignored during replay. */
class JournalRuntimeStore(private val file: File) : RuntimeStore {
    private val lock = Any()
    init { file.parentFile?.mkdirs(); if (!file.exists()) file.createNewFile() }
    override fun saveRun(run: Run) = synchronized(lock) { append(RunRecord.encode(run)) }
    override fun loadRun(runId: String, catalog: ActionCatalog): Run? = synchronized(lock) { replay().runs[runId]?.toRun(catalog) }
    override fun reserveEffects(invocations: List<CapabilityInvocation>): EffectReservation = synchronized(lock) {
        if (invocations.map { it.effectId }.distinct().size != invocations.size) return@synchronized EffectReservation.CONFLICT
        val state = replay(); val incoming = invocations.map { StoredEffect.from(it, EffectStatus.RESERVED) }
        if (incoming.any { e -> state.effects[e.effectId]?.let { !it.compatibleWith(e) } == true }) return@synchronized EffectReservation.CONFLICT
        if (incoming.any { state.effects[it.effectId]?.status != EffectStatus.CONFIRMED_NOT_EXECUTED && state.effects.containsKey(it.effectId) }) return@synchronized EffectReservation.REPLAY_BLOCKED
        incoming.forEach { append(it.encode()) }; EffectReservation.RESERVED
    }
    override fun completeEffect(effectId: String) = synchronized(lock) { append("X|${Codec.encode(effectId)}|COMPLETED") }
    override fun markEffectUnknown(effectId: String) = synchronized(lock) { append("X|${Codec.encode(effectId)}|UNKNOWN") }
    override fun reconcileEffect(effectId: String, decision: EffectReconciliationDecision): EffectReconciliationResult = synchronized(lock) {
        val current = replay().effects[effectId] ?: return@synchronized EffectReconciliationResult.NOT_FOUND
        if (current.status != EffectStatus.UNKNOWN) return@synchronized EffectReconciliationResult.NOT_UNKNOWN
        append("X|${Codec.encode(effectId)}|${decision.toStatus().name}"); EffectReconciliationResult.RECONCILED
    }
    override fun unknownEffects(): List<EffectRecord> = synchronized(lock) { replay().effects.values.filter { it.status == EffectStatus.UNKNOWN }.map { it.record() } }
    override fun recoverInterruptedEffects(): Int = synchronized(lock) {
        val reserved = replay().effects.values.filter { it.status == EffectStatus.RESERVED }
        reserved.forEach { append("X|${Codec.encode(it.effectId)}|UNKNOWN") }
        reserved.size
    }
    private fun append(record: String) {
        FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND).use { channel ->
            channel.write(ByteBuffer.wrap((record + "\n").toByteArray(StandardCharsets.UTF_8))); channel.force(true)
        }
    }
    private fun replay(): State {
        val runs = linkedMapOf<String, RunRecord>(); val effects = linkedMapOf<String, StoredEffect>()
        file.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "R" -> RunRecord.decode(parts)?.let { runs[it.id] = it }
                "E" -> StoredEffect.decode(parts)?.let { effects[it.effectId] = it }
                "X" -> if (parts.size == 3) {
                    val effectId = runCatching { Codec.decode(parts[1]) }.getOrNull(); val status = runCatching { EffectStatus.valueOf(parts[2]) }.getOrNull()
                    if (effectId != null && status != null) effects[effectId]?.let { effects[effectId] = it.copy(status = status) }
                }
            }
        }
        return State(runs, effects)
    }
    private data class State(val runs: MutableMap<String, RunRecord>, val effects: MutableMap<String, StoredEffect>)
}

private fun EffectReconciliationDecision.toStatus(): EffectStatus = when (this) {
    EffectReconciliationDecision.CONFIRMED_COMPLETED -> EffectStatus.COMPLETED
    EffectReconciliationDecision.CONFIRMED_NOT_EXECUTED -> EffectStatus.CONFIRMED_NOT_EXECUTED
}

private data class RunRecord(
    val id: String, val source: ActivationSource, val actionId: String, val actionVersion: Int, val identity: String,
    val status: RunStatus, val input: Map<String, String>, val output: Map<String, String>, val denialReason: String?,
    val approvalId: String?, val evidence: Evidence?
) {
    fun toRun(catalog: ActionCatalog): Run? = catalog.get(actionId)?.let { action ->
        Run(id, ActivationRequest(source, actionId, input, identity), status, action.copy(version = actionVersion), output, evidence, denialReason, approvalId)
    }
    companion object {
        fun encode(run: Run): String {
            val e = run.evidence
            return listOf("R", Codec.encode(run.id), run.activation.source.name, Codec.encode(run.activation.actionId), run.action.version.toString(), Codec.encode(run.activation.identity), run.status.name,
                Codec.encodeMap(run.activation.input), Codec.encodeMap(run.output), Codec.encodeNullable(run.denialReason), Codec.encodeNullable(run.approvalId),
                if (e == null) "0" else "1", if (e == null) "" else Codec.encode(e.authorizedBy ?: ""), if (e == null) "" else Codec.encodeInvocations(e.capabilityInvocations),
                if (e == null) "" else Codec.encodeObservations(e.observations), if (e == null) "" else e.verification.passed.toString(), if (e == null) "" else Codec.encode(e.verification.reason)).joinToString("|")
        }
        fun decode(parts: List<String>): RunRecord? {
            if (parts[0] != "R" || (parts.size != 16 && parts.size != 17)) return null
            return runCatching {
                val legacy = parts.size == 16
                val approvalId = if (legacy) null else Codec.decodeNullable(parts[10])
                val evidenceFlag = parts[if (legacy) 10 else 11]
                val evidence = if (evidenceFlag == "1") {
                    val base = if (legacy) 11 else 12
                    Evidence(runId = Codec.decode(parts[1]), actionId = Codec.decode(parts[3]), actionVersion = parts[4].toInt(), activationSource = ActivationSource.valueOf(parts[2]),
                        authorizedBy = Codec.decode(parts[base]).ifBlank { null }, capabilityInvocations = Codec.decodeInvocations(parts[base + 1]), observations = Codec.decodeObservations(parts[base + 2]),
                        verification = Verification(parts[base + 3].toBooleanStrict(), Codec.decode(parts[base + 4])))
                } else null
                RunRecord(Codec.decode(parts[1]), ActivationSource.valueOf(parts[2]), Codec.decode(parts[3]), parts[4].toInt(), Codec.decode(parts[5]), RunStatus.valueOf(parts[6]), Codec.decodeMap(parts[7]), Codec.decodeMap(parts[8]), Codec.decodeNullable(parts[9]), approvalId, evidence)
            }.getOrNull()
        }
    }
}

private object Codec {
    private val encoder = Base64.getUrlEncoder().withoutPadding(); private val decoder = Base64.getUrlDecoder()
    fun encode(value: String): String = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    fun decode(value: String): String = if (value.isBlank()) "" else String(decoder.decode(value), StandardCharsets.UTF_8)
    fun encodeNullable(value: String?): String = value?.let { "1:${encode(it)}" } ?: "0"
    fun decodeNullable(value: String): String? = if (value == "0" || value.isBlank()) null else decode(value.removePrefix("1:"))
    fun encodeSet(value: Set<String>): String = value.toList().sorted().joinToString(",") { encode(it) }
    fun decodeSet(value: String): Set<String> = if (value.isBlank()) emptySet() else value.split(',').map(::decode).toSet()
    fun encodeMap(value: Map<String, String>): String = value.toSortedMap().entries.joinToString(",") { "${encode(it.key)}.${encode(it.value)}" }
    fun decodeMap(value: String): Map<String, String> = if (value.isBlank()) emptyMap() else value.split(',').associate { val separator = it.indexOf('.'); require(separator > 0); decode(it.substring(0, separator)) to decode(it.substring(separator + 1)) }
    fun encodeInvocations(value: List<CapabilityInvocation>): String = value.joinToString("~") { i -> listOf(encode(i.id), encode(i.runId), encode(i.capabilityId), encode(i.actionId), i.actionVersion.toString(), encode(i.effectId), encodeSet(i.scope), encode(i.attributedTo), encodeMap(i.parameters)).joinToString("^") }
    fun decodeInvocations(value: String): List<CapabilityInvocation> = if (value.isBlank()) emptyList() else value.split('~').map { raw -> val p = raw.split('^'); require(p.size == 9); CapabilityInvocation(decode(p[0]), decode(p[1]), decode(p[2]), decode(p[3]), p[4].toInt(), decode(p[5]), decodeSet(p[6]), decode(p[7]), decodeMap(p[8])) }
    fun encodeObservations(value: List<Observation>): String = value.joinToString("~") { "${encode(it.key)}^${encode(it.value)}" }
    fun decodeObservations(value: String): List<Observation> = if (value.isBlank()) emptyList() else value.split('~').map { val p = it.split('^'); require(p.size == 2); Observation(decode(p[0]), decode(p[1])) }
}
