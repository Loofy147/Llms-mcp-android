package com.hicham.llmchat.runtime.t2

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

internal enum class T2CallerState {
    DISPATCH_RESERVED,
    DISPATCHING,
    UNKNOWN_OUTCOME,
    COMPLETED,
    KNOWN_NOT_EXECUTED,
}

internal data class T2CallerOperation(val state: T2CallerState)

internal class T2CallerStore(context: Context, namespace: String = "main") {
    private val root = File(context.filesDir, "t2-caller/$namespace/operations")

    init {
        check(root.mkdirs() || root.isDirectory) { "Could not create $root" }
    }

    @Synchronized
    fun reserve(operationId: String) {
        write(operationId, T2CallerOperation(T2CallerState.DISPATCH_RESERVED))
    }

    @Synchronized
    fun markDispatching(operationId: String) {
        requireExisting(operationId)
        write(operationId, T2CallerOperation(T2CallerState.DISPATCHING))
    }

    @Synchronized
    fun markUnknown(operationId: String) {
        requireExisting(operationId)
        write(operationId, T2CallerOperation(T2CallerState.UNKNOWN_OUTCOME))
    }

    @Synchronized
    fun markCompleted(operationId: String) {
        requireExisting(operationId)
        write(operationId, T2CallerOperation(T2CallerState.COMPLETED))
    }

    @Synchronized
    fun markKnownNotExecuted(operationId: String) {
        requireExisting(operationId)
        write(operationId, T2CallerOperation(T2CallerState.KNOWN_NOT_EXECUTED))
    }

    @Synchronized
    fun get(operationId: String): T2CallerOperation? = read(operationId)

    private fun requireExisting(operationId: String): T2CallerOperation =
        requireNotNull(read(operationId)) { "Unknown operation_id=$operationId" }

    private fun fileFor(operationId: String): File =
        File(root, operationId.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private fun read(operationId: String): T2CallerOperation? {
        val file = fileFor(operationId)
        if (!file.isFile) return null
        val properties = Properties()
        FileInputStream(file).use { properties.load(it) }
        return T2CallerOperation(T2CallerState.valueOf(properties.getProperty("state")))
    }

    private fun write(operationId: String, operation: T2CallerOperation) {
        val target = fileFor(operationId)
        val temp = File(root, ".${target.name}.tmp")
        val properties = Properties().apply { setProperty("state", operation.state.name) }

        FileOutputStream(temp).use { output ->
            properties.store(output, null)
            output.flush()
            output.fd.sync()
        }

        check(temp.renameTo(target)) {
            temp.delete()
            "Could not atomically replace ${target.absolutePath}"
        }
    }
}
