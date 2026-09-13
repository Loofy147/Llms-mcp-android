package com.hicham.llmchat.runtime

import java.io.InputStream
import java.security.MessageDigest

object DeveloperHashAction {
    const val FILE_HASH = "dev.file.hash"
    const val WORKSPACE_FILE_HASH = "workspace.file.hash"

    fun catalog(): List<ActionDefinition> = listOf(fileHash())

    private fun fileHash() = ActionDefinition(
        id = FILE_HASH,
        version = 1,
        purpose = "Compute a SHA-256 digest for one file from an explicitly user-granted workspace.",
        capabilities = listOf(CapabilityDescriptor(WORKSPACE_FILE_HASH, EffectClass.READ_ONLY)),
        reduce = { _, results ->
            val result = results.single()
            ActionExecution(
                output = result.output,
                observations = result.observations,
                postcondition = result.postcondition
            )
        },
        plan = { input ->
            require(input["grant_id"].orEmpty().isNotBlank()) { "grant_id is required" }
            require(input.containsKey("path")) { "path is required" }
            ActionPlan(
                listOf(
                    CapabilityInvocationSpec(
                        capabilityId = WORKSPACE_FILE_HASH,
                        parameters = mapOf(
                            "grant_id" to input["grant_id"].orEmpty(),
                            "path" to input["path"].orEmpty()
                        )
                    )
                )
            )
        }
    )

    fun hash(
        inputStream: InputStream,
        path: String
    ): CapabilityExecution {
        inputStream.use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var bytes = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                bytes += read
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            return CapabilityExecution(
                output = mapOf(
                    "path" to path,
                    "bytes" to bytes.toString(),
                    "sha256" to sha256
                ),
                observations = listOf(
                    Observation("path", path),
                    Observation("bytes", bytes.toString()),
                    Observation("sha256", sha256)
                )
            )
        }
    }
}
