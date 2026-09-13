package com.hicham.llmchat.runtime

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DeveloperHashActionTest {
    @Test
    fun computesDeterministicSha256WithoutReturningContent() {
        val execution = DeveloperHashAction.hash(
            ByteArrayInputStream("hello\nworld".toByteArray(Charsets.UTF_8)),
            "src/main.kt"
        )

        assertEquals("src/main.kt", execution.output["path"])
        assertEquals("11", execution.output["bytes"])
        assertEquals("26c60a61d01db5836ca70fefd44a6a016620413c8ef5f259a6c5612d4f79d3b8", execution.output["sha256"])
        assertEquals(null, execution.output["content"])
        assertEquals(1, execution.observations.count { it.key == "sha256" })
    }

    @Test
    fun rejectsFilesAboveHashLimit() {
        val oversized = ByteArray(16 * 1024 * 1024 + 1)
        try {
            DeveloperHashAction.hash(ByteArrayInputStream(oversized), "large.bin")
            fail("Expected oversized input to be rejected")
        } catch (_: WorkspaceAccessException) {
            // expected
        }
    }
}
