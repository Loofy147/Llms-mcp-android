package com.hicham.llmchat.runtime

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

interface ApprovalStore {
    fun save(context: ApprovalContext)
    fun load(approvalId: String): ApprovalContext?
    fun consume(approvalId: String, runId: String, requesterIdentity: String, fingerprint: String, decision: ApprovalDecision, approverIdentity: String): ApprovalConsumption
}

enum class ApprovalDecision { APPROVED, DENIED }
enum class ApprovalStatus { PENDING, APPROVED, DENIED }
enum class ApprovalConsumption { CONSUMED, NOT_FOUND, NOT_PENDING, CONFLICT }

data class ApprovalContext(
    val approvalId: String = UUID.randomUUID().toString(),
    val runId: String,
    val requesterIdentity: String,
    val actionId: String,
    val actionVersion: Int,
    val input: Map<String, String>,
    val plannedInvocations: List<CapabilityInvocationSpec>,
    val fingerprint: String,
    val status: ApprovalStatus = ApprovalStatus.PENDING,
    val resolvedBy: String? = null
)

class InMemoryApprovalStore : ApprovalStore {
    private val approvals = linkedMapOf<String, ApprovalContext>()
    @Synchronized override fun save(context: ApprovalContext) { approvals[context.approvalId] = context }
    @Synchronized override fun load(approvalId: String): ApprovalContext? = approvals[approvalId]
    @Synchronized override fun consume(approvalId: String, runId: String, requesterIdentity: String, fingerprint: String, decision: ApprovalDecision, approverIdentity: String): ApprovalConsumption {
        val current = approvals[approvalId] ?: return ApprovalConsumption.NOT_FOUND
        if (current.status != ApprovalStatus.PENDING) return ApprovalConsumption.NOT_PENDING
        if (current.runId != runId || current.requesterIdentity != requesterIdentity || current.fingerprint != fingerprint) return ApprovalConsumption.CONFLICT
        approvals[approvalId] = current.copy(status = decision.toStatus(), resolvedBy = approverIdentity)
        return ApprovalConsumption.CONSUMED
    }
}

class JournalApprovalStore(private val file: File) : ApprovalStore {
    private val lock = Any()
    init { file.parentFile?.mkdirs(); if (!file.exists()) file.createNewFile() }
    override fun save(context: ApprovalContext) = synchronized(lock) { append(Record.encode(context)) }
    override fun load(approvalId: String): ApprovalContext? = synchronized(lock) { replay()[approvalId]?.toContext() }
    override fun consume(approvalId: String, runId: String, requesterIdentity: String, fingerprint: String, decision: ApprovalDecision, approverIdentity: String): ApprovalConsumption = synchronized(lock) {
        val current = replay()[approvalId] ?: return@synchronized ApprovalConsumption.NOT_FOUND
        if (current.status != ApprovalStatus.PENDING) return@synchronized ApprovalConsumption.NOT_PENDING
        if (current.runId != runId || current.requesterIdentity != requesterIdentity || current.fingerprint != fingerprint) return@synchronized ApprovalConsumption.CONFLICT
        append(Record.encode(current.toContext().copy(status = decision.toStatus(), resolvedBy = approverIdentity)))
        ApprovalConsumption.CONSUMED
    }
    private fun append(record: String) { FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND).use { channel -> channel.write(ByteBuffer.wrap((record + "\n").toByteArray(StandardCharsets.UTF_8))); channel.force(true) } }
    private fun replay(): Map<String, Record> {
        val records = linkedMapOf<String, Record>()
        file.forEachLine(Charsets.UTF_8) { line -> if (line.isNotBlank()) Record.decode(line.split('|'))?.let { records[it.approvalId] = it } }
        return records
    }
    private data class Record(val approvalId: String, val runId: String, val requesterIdentity: String, val actionId: String, val actionVersion: Int, val input: Map<String, String>, val plannedInvocations: List<CapabilityInvocationSpec>, val fingerprint: String, val status: ApprovalStatus, val resolvedBy: String?) {
        fun toContext() = ApprovalContext(approvalId, runId, requesterIdentity, actionId, actionVersion, input, plannedInvocations, fingerprint, status, resolvedBy)
        companion object {
            fun encode(context: ApprovalContext): String = listOf("A", Codec.encode(context.approvalId), Codec.encode(context.runId), Codec.encode(context.requesterIdentity), Codec.encode(context.actionId), context.actionVersion.toString(), Codec.encodeMap(context.input), Codec.encodeInvocations(context.plannedInvocations), Codec.encode(context.fingerprint), context.status.name, Codec.encodeNullable(context.resolvedBy)).joinToString("|")
            fun decode(parts: List<String>): Record? = if (parts.size != 11 || parts[0] != "A") null else runCatching { Record(Codec.decode(parts[1]), Codec.decode(parts[2]), Codec.decode(parts[3]), Codec.decode(parts[4]), parts[5].toInt(), Codec.decodeMap(parts[6]), Codec.decodeInvocations(parts[7]), Codec.decode(parts[8]), ApprovalStatus.valueOf(parts[9]), Codec.decodeNullable(parts[10])) }.getOrNull()
        }
    }
    private object Codec {
        private val encoder = Base64.getUrlEncoder().withoutPadding(); private val decoder = Base64.getUrlDecoder()
        fun encode(value: String) = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        fun decode(value: String) = if (value.isBlank()) "" else String(decoder.decode(value), StandardCharsets.UTF_8)
        fun encodeNullable(value: String?) = value?.let { "1:${encode(it)}" } ?: "0"
        fun decodeNullable(value: String): String? = if (value == "0" || value.isBlank()) null else decode(value.removePrefix("1:"))
        fun encodeMap(value: Map<String, String>) = value.toSortedMap().entries.joinToString(",") { "${encode(it.key)}.${encode(it.value)}" }
        fun decodeMap(value: String): Map<String, String> = if (value.isBlank()) emptyMap() else value.split(',').associate { raw -> val i = raw.indexOf('.'); require(i > 0); decode(raw.substring(0, i)) to decode(raw.substring(i + 1)) }
        fun encodeSet(value: Set<String>) = value.toList().sorted().joinToString(",") { encode(it) }
        fun decodeSet(value: String): Set<String> = if (value.isBlank()) emptySet() else value.split(',').map(::decode).toSet()
        fun encodeInvocations(value: List<CapabilityInvocationSpec>) = value.joinToString("~") { spec -> listOf(encode(spec.capabilityId), encodeSet(spec.scope), encodeMap(spec.parameters), encodeNullable(spec.idempotencyKey)).joinToString("^") }
        fun decodeInvocations(value: String): List<CapabilityInvocationSpec> = if (value.isBlank()) emptyList() else value.split('~').map { raw -> val p = raw.split('^'); require(p.size == 4); CapabilityInvocationSpec(decode(p[0]), decodeSet(p[1]), decodeMap(p[2]), decodeNullable(p[3])) }
    }
}

private fun ApprovalDecision.toStatus(): ApprovalStatus = when (this) { ApprovalDecision.APPROVED -> ApprovalStatus.APPROVED; ApprovalDecision.DENIED -> ApprovalStatus.DENIED }
