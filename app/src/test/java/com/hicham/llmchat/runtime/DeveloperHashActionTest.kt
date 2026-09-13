package com.hicham.llmchat.runtime

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
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
        assertEquals("68d8b5f2abf2d8c3b0cbf4ef3f4fef4e8ad6eb7f3f9cb2f6e0ed7db7a4f0f7d6", execution.output["sha256"])
        assertEquals(null, execution.output["content"])
        assertEquals(1, execution.observations.count { it.key == "sha256" })
    }
}
