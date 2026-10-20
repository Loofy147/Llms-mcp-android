package com.hicham.llmchat.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WorkspacePathTest {
    @Test
    fun normalizesSeparatorsAndEmptySegments() {
        assertEquals("src/main.kt", WorkspacePath.normalize("src//main.kt"))
        assertEquals("src/main.kt", WorkspacePath.normalize("src\\main.kt"))
        assertEquals("", WorkspacePath.normalize(""))
    }

    @Test
    fun rejectsAbsolutePaths() {
        assertRejects("/etc/passwd")
        assertRejects("\\etc\\passwd")
    }

    @Test
    fun rejectsTraversal() {
        assertRejects("../secret")
        assertRejects("src/../../secret")
        assertRejects("src/./secret")
    }

    @Test
    fun rejectsNullCharacters() {
        assertRejects("src\u0000secret")
    }

    private fun assertRejects(path: String) {
        try {
            WorkspacePath.normalize(path)
            fail("Expected WorkspaceAccessException for $path")
        } catch (_: WorkspaceAccessException) {
            // expected
        }
    }
}
