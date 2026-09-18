package com.hicham.llmchat.runtime

object WorkspacePath {
    fun normalize(relativePath: String): String {
        val normalizedSeparators = relativePath.replace('\\', '/')
        require(normalizedSeparators.isNotEmpty() || relativePath.isEmpty()) {
            "Workspace path is invalid"
        }
        if (normalizedSeparators.startsWith('/') || normalizedSeparators.contains('\u0000')) {
            throw WorkspaceAccessException("Workspace path must be relative")
        }
        val segments = normalizedSeparators.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) {
            throw WorkspaceAccessException("Workspace path traversal is not allowed")
        }
        return segments.joinToString("/")
    }
}
