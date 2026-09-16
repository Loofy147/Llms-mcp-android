package com.hicham.llmchat.runtime.t2

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.util.Properties

internal enum class T2OperationState {
    RECEIVED,
    EFFECT_APPLIED,
    COMPLETED,
}

internal data class T2Operation(
    val state: T2OperationState,
    val effectCount: Int,
)

internal class T2ProviderStore(context: Context) {
    private val root = File(context.filesDir, "t2-provider/operations")

    init {
        check(root.mkdirs() || root.isDirectory) { "Could not create $root" }
    }

    @Synchronized
    fun receive(operationId: String): T2Operation {
        val current = read(operationId)
        if (current != null) return current
        val created = T2Operation(T2OperationState.RECEIVED, 0)
        write(operationId, created)
        return created
    }

    @Synchronized
    fun applyEffect(operationId: String): T2Operation {
        val current = requireOperation(operationId)
        return when (current.state) {
            T2OperationState.RECEIVED -> {
                val next = T2Operation(T2OperationState.EFFECT_APPLIED, 1)
                write(operationId, next)
                next
            }
            T2OperationState.EFFECT_APPLIED,
            T2OperationState.COMPLETED -> current
        }
    }

    @Synchronized
    fun complete(operationId: String): T2Operation {
        val current = requireOperation(operationId)
        if (current.state == T2OperationState.COMPLETED) return current
        val next = T2Operation(T2OperationState.COMPLETED, current.effectCount)
        write(operationId, next)
        return next
    }

    @Synchronized
    fun get(operationId: String): T2Operation? = read(operationId)

    private fun requireOperation(operationId: String): T2Operation =
        requireNotNull(read(operationId)) { "Unknown operation_id=$operationId" }

    private fun fileFor(operationId: String): File =
        File(root, operationId.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private fun read(operationId: String): T2Operation? {
        val file = fileFor(operationId)
        if (!file.isFile) return null
        val properties = Properties()
        FileInputStream(file).use { properties.load(it) }
        return T2Operation(
            state = T2OperationState.valueOf(properties.getProperty("state")),
            effectCount = properties.getProperty("effect_count").toInt(),
        )
    }

    private fun write(operationId: String, operation: T2Operation) {
        val target = fileFor(operationId)
        val temp = File(root, ".${target.name}.tmp")
        val properties = Properties().apply {
            setProperty("state", operation.state.name)
            setProperty("effect_count", operation.effectCount.toString())
        }

        FileOutputStream(temp).use { output ->
            properties.store(output, null)
            output.flush()
            output.fd.sync()
        }

        if (!temp.renameTo(target)) {
            temp.delete()
            error("Could not atomically replace ${target.absolutePath}")
        }

        FileChannel.open(target.toPath()).use { channel -> channel.force(true) }
    }
}
