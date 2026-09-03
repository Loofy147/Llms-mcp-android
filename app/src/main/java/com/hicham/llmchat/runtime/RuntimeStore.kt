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
}

enum class EffectReservation { RESERVED, REPLAY_BLOCKED, CONFLICT }

class InMemoryRuntimeStore : RuntimeStore {
    private val runs = linkedMapOf<String, Run>()
    private val effects = linkedMapOf<String, StoredEffect>()

    @Synchronized
    override fun saveRun(run: Run) { runs[run.id] = run }

    @Synchronized
    override fun loadRun(runId: String, catalog: ActionCatalog): Run? = runs[runId]

    @Synchronized
    override fun reserveEffects(invocations: List<CapabilityInvocation>): EffectReservation {
        if (invocations.map { it.effectId }.distinct().size != invocations.size) return EffectReservation.CONFLICT
        val incoming = invocations.map { StoredEffect.from(it, EffectStatus.RESERVED) }
        if (incoming.any { effect -> effects[effect.effectId]?.let { !it.compatibleWith(effect) } == true }) {
            return EffectReservation.CONFLICT
        }
        if (incoming.any { effects.containsKey(it.effectId) }) return EffectReservation.REPLAY_BLOCKED
        incoming.forEach { effects[it.effectId] = it }
        return EffectReservation.RESERVED
    }

    @Synchronized
    override fun completeEffect(effectId: String) {
        effects[effectId]?.let { effects[effectId] = it.copy(status = EffectStatus.COMPLETED) }
    }

    @Synchronized
    override fun markEffectUnknown(effectId: String) {
        effects[effectId]?.let { effects[effectId] = it.copy(status = EffectStatus.UNKNOWN) }
    }
}

private enum class EffectStatus { RESERVED, COMPLETED, UNKNOWN }

private data class StoredEffect(
    val effectId: String,
    val runId: String,
    val capabilityId: String,
    val actionId: String,
    val actionVersion: Int,
    val scope: Set<String>,
    val attributedTo: String,
    val parameters: Map<String, String>,
    val status: EffectStatus
) {
    fun compatibleWith(other: StoredEffect): Boolean =
        effectId == other.effectId && capabilityId == other.capabilityId && actionId == other.actionId &&
            actionVersion == other.actionVersion && scope == other.scope && attributedTo == other.attributedTo &&
            parameters == other.parameters

    fun encode(): String = listOf(
        "E", Codec.encode(effectId), Codec.encode(runId), Codec.encode(capabilityId), Codec.encode(actionId),
        actionVersion.toString(), Codec.encodeSet(scope), Codec.encode(attributedTo), Codec.encodeMap(parameters), status.name
    ).joinToString("|")

    companion object {
        fun from(invocation: CapabilityInvocation, status: EffectStatus) = StoredEffect(
            effectId = invocation.effectId, runId = invocation.runId, capabilityId = invocation.capabilityId,
            actionId = invocation.actionId, actionVersion = invocation.actionVersion, scope = invocation.scope,
            attributedTo = invocation.attributedTo, parameters = invocation.parameters, status = status
        )

        fun decode(parts: List<String>): StoredEffect? {
            if (parts.size != 10 || parts[0] != "E") return null
            return runCatching {
                StoredEffect(
                    effectId = Codec.decode(parts[1]), runId = Codec.decode(parts[2]), capabilityId = Codec.decode(parts[3]),
                    actionId = Codec.decode(parts[4]), actionVersion = parts[5].toInt(), scope = Codec.decodeSet(parts[6]),
                    attributedTo = Codec.decode(parts[7]), parameters = Codec.decodeMap(parts[8]), status = EffectStatus.valueOf(parts[9])
                )
            }.getOrNull()
        }
    }
}

/** Append-only journal; a torn final record is ignored during replay. */
class JournalRuntimeStore(private val file: File) : RuntimeStore {
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
    }

    override fun saveRun(run: Run) = synchronized(lock) { append(RunRecord.encode(run)) }

    override fun loadRun(runId: String, catalog: ActionCatalog): Run? = synchronized(lock) {
        replay().runs[runId]?.toRun(catalog)
    }

    override fun reserveEffects(invocations: List<CapabilityInvocation>): EffectReservation = synchronized(lock) {
        if (invocations.map { it.effectId }.distinct().size != invocations.size) return@synchronized EffectReservation.CONFLICT
        val state = replay()
        val incoming = invocations.map { StoredEffect.from(it, EffectStatus.RESERVED) }
        if (incoming.any { effect -> state.effects[effect.effectId]?.let { !it.compatibleWith(effect) } == true }) {
            return@synchronized EffectReservation.CONFLICT
        }
        if (incoming.any { state.effects.containsKey(it.effectId) }) return@synchronized EffectReservation.REPLAY_BLOCKED
        incoming.forEach { append(it.encode()) }
        EffectReservation.RESERVED
    }

    override fun completeEffect(effectId: String) = synchronized(lock) {
        append("X|${Codec.encode(effectId)}|COMPLETED")
    }

    override fun markEffectUnknown(effectId: String) = synchronized(lock) {
        append("X|${Codec.encode(effectId)}|UNKNOWN")
    }

    private fun append(record: String) {
        val bytes = (record + "\n").toByteArray(StandardCharsets.UTF_8)
        FileChannel.open(
            file.toPath(),
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.APPEND
        ).use { channel ->
            channel.write(ByteBuffer.wrap(bytes))
            channel.force(true)
        }
    }

    private fun replay(): State {
        val runs = linkedMapOf<String, RunRecord>()
        val effects = linkedMapOf<String, StoredEffect>()
        file.forEachLine(Charsets.UTF_8) { line ->
            if (line.isBlank()) return@forEachLine
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "R" -> RunRecord.decode(parts)?.let { runs[it.id] = it }
                "E" -> StoredEffect.decode(parts)?.let { effects[it.effectId] = it }
                "X" -> if (parts.size == 3) {
                    val effectId = Codec.decode(parts[1])
                    val current = effects[effectId]
                    val status = runCatching { EffectStatus.valueOf(parts[2]) }.getOrNull()
                    if (current != null && status != null) effects[effectId] = current.copy(status = status)
                }
            }
        }
        return State(runs, effects)
    }

    private data class State(val runs: MutableMap<String, RunRecord>, val effects: MutableMap<String, StoredEffect>)
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
    val evidence: Evidence?
) {
    fun toRun(catalog: ActionCatalog): Run? {
        val action = catalog.get(actionId) ?: return null
        return Run(
            id = id,
            activation = ActivationRequest(source, actionId, input, identity),
            status = status,
            action = action.copy(version = actionVersion),
            output = output,
            evidence = evidence,
            denialReason = denialReason
        )
    }

    companion object {
        fun encode(run: Run): String {
            val e = run.evidence
            return listOf(
                "R", Codec.encode(run.id), run.activation.source.name, Codec.encode(run.activation.actionId),
                run.action.version.toString(), Codec.encode(run.activation.identity), run.status.name,
                Codec.encodeMap(run.activation.input), Codec.encodeMap(run.output), Codec.encodeNullable(run.denialReason),
                if (e == null) "0" else "1", if (e == null) "" else Codec.encode(e.authorizedBy ?: ""),
                if (e == null) "" else Codec.encodeInvocations(e.capabilityInvocations),
                if (e == null) "" else Codec.encodeObservations(e.observations),
                if (e == null) "" else e.verification.passed.toString(), if (e == null) "" else Codec.encode(e.verification.reason)
            ).joinToString("|")
        }

        fun decode(parts: List<String>): RunRecord? {
            if (parts.size != 16 || parts[0] != "R") return null
            return runCatching {
                val evidence = if (parts[10] == "1") Evidence(
                    runId = Codec.decode(parts[1]), actionId = Codec.decode(parts[3]), actionVersion = parts[4].toInt(),
                    activationSource = ActivationSource.valueOf(parts[2]), authorizedBy = Codec.decode(parts[11]).ifBlank { null },
                    capabilityInvocations = Codec.decodeInvocations(parts[12]), observations = Codec.decodeObservations(parts[13]),
                    verification = Verification(parts[14].toBooleanStrict(), Codec.decode(parts[15]))
                ) else null
                RunRecord(
                    id = Codec.decode(parts[1]), source = ActivationSource.valueOf(parts[2]), actionId = Codec.decode(parts[3]),
                    actionVersion = parts[4].toInt(), identity = Codec.decode(parts[5]), status = RunStatus.valueOf(parts[6]),
                    input = Codec.decodeMap(parts[7]), output = Codec.decodeMap(parts[8]), denialReason = Codec.decodeNullable(parts[9]), evidence = evidence
                )
            }.getOrNull()
        }
    }
}

private object Codec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(value: String): String = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    fun decode(value: String): String = if (value.isBlank()) "" else String(decoder.decode(value), StandardCharsets.UTF_8)
    fun encodeNullable(value: String?): String = value?.let { "1:${encode(it)}" } ?: "0"
    fun decodeNullable(value: String): String? = if (value == "0" || value.isBlank()) null else decode(value.removePrefix("1:"))
    fun encodeSet(value: Set<String>): String = value.toList().sorted().joinToString(",") { encode(it) }
    fun decodeSet(value: String): Set<String> = if (value.isBlank()) emptySet() else value.split(',').map(::decode).toSet()
    fun encodeMap(value: Map<String, String>): String = value.toSortedMap().entries.joinToString(",") { "${encode(it.key)}.${encode(it.value)}" }
    fun decodeMap(value: String): Map<String, String> = if (value.isBlank()) emptyMap() else value.split(',').associate {
        val separator = it.indexOf('.')
        require(separator > 0)
        decode(it.substring(0, separator)) to decode(it.substring(separator + 1))
    }
    fun encodeInvocations(value: List<CapabilityInvocation>): String = value.joinToString("~") { i ->
        listOf(encode(i.id), encode(i.runId), encode(i.capabilityId), encode(i.actionId), i.actionVersion.toString(),
            encode(i.effectId), encodeSet(i.scope), encode(i.attributedTo), encodeMap(i.parameters)).joinToString("^")
    }
    fun decodeInvocations(value: String): List<CapabilityInvocation> = if (value.isBlank()) emptyList() else value.split('~').map { raw ->
        val p = raw.split('^')
        require(p.size == 9)
        CapabilityInvocation(
            id = decode(p[0]), runId = decode(p[1]), capabilityId = decode(p[2]), actionId = decode(p[3]),
            actionVersion = p[4].toInt(), effectId = decode(p[5]), scope = decodeSet(p[6]),
            attributedTo = decode(p[7]), parameters = decodeMap(p[8])
        )
    }
    fun encodeObservations(value: List<Observation>): String = value.joinToString("~") { "${encode(it.key)}^${encode(it.value)}" }
    fun decodeObservations(value: String): List<Observation> = if (value.isBlank()) emptyList() else value.split('~').map {
        val p = it.split('^')
        require(p.size == 2)
        Observation(decode(p[0]), decode(p[1]))
    }
}
