package com.hicham.llmchat.runtime

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperActionsTest {
    @Test
    fun readsUtf8TextAndProducesDeterministicObservation() {
        val execution = DeveloperActions.readText(
            ByteArrayInputStream("hello\nworld".toByteArray(Charsets.UTF_8)),
            "src/main.kt"
        )

        assertEquals("src/main.kt", execution.output["path"])
        assertEquals("hello\nworld", execution.output["content"])
        assertEquals("11", execution.output["bytes"])
        assertEquals("false", execution.output["truncated"])
        assertEquals(64, execution.output["sha256"]?.length)
        assertTrue(execution.observations.any { it.key == "sha256" })
    }

    @Test
    fun rejectsMalformedUtf8() {
        try {
            DeveloperActions.readText(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)), "bad.txt")
            throw AssertionError("Expected malformed UTF-8 to be rejected")
        } catch (_: WorkspaceAccessException) {
            // expected
        }
    }

    @Test
    fun rejectsFilesAboveReadLimit() {
        val oversized = ByteArray(64 * 1024 + 1)
        try {
            DeveloperActions.readText(ByteArrayInputStream(oversized), "large.txt")
            throw AssertionError("Expected oversized input to be rejected")
        } catch (_: WorkspaceAccessException) {
            // expected
        }
    }
}
