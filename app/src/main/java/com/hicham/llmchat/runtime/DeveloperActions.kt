package com.hicham.llmchat.runtime

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object DeveloperActions {
    const val FILE_READ = "dev.file.read"
    const val WORKSPACE_FILE_READ = "workspace.file.read"
    private const val MAX_BYTES = 64 * 1024

    fun catalog(): List<ActionDefinition> = listOf(fileRead())

    private fun fileRead() = ActionDefinition(
        id = FILE_READ,
        version = 1,
        purpose = "Read one UTF-8 text file from an explicitly user-granted workspace.",
        capabilities = listOf(CapabilityDescriptor(WORKSPACE_FILE_READ, EffectClass.READ_ONLY)),
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
                        capabilityId = WORKSPACE_FILE_READ,
                        scope = setOf("workspace:${input["grant_id"]}"),
                        parameters = mapOf(
                            "grant_id" to input["grant_id"].orEmpty(),
                            "path" to input["path"].orEmpty()
                        ),
                        idempotencyKey = "${input["grant_id"]}:${input["path"].orEmpty()}"
                    )
                )
            )
        }
    )

    fun readText(
        inputStream: InputStream,
        path: String
    ): CapabilityExecution {
        val bytes = readBounded(inputStream, MAX_BYTES)
        val text = decodeUtf8Strict(bytes)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return CapabilityExecution(
            output = mapOf(
                "path" to path,
                "content" to text,
                "bytes" to bytes.size.toString(),
                "sha256" to digest,
                "truncated" to "false"
            ),
            observations = listOf(
                Observation("path", path),
                Observation("bytes", bytes.size.toString()),
                Observation("sha256", digest)
            )
        )
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        input.use { stream ->
            val buffer = ByteArray(8192)
            var total = 0
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw WorkspaceAccessException("File exceeds maximum readable size of $maxBytes bytes")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String {
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw WorkspaceAccessException("File is not valid UTF-8 text")
        }
    }
}
