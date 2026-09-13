package com.hicham.llmchat.runtime

enum class WorkspaceOperation { READ, LIST, HASH }

data class WorkspaceGrant(
    val id: String,
    val displayName: String,
    val authority: String,
    val treeUri: String,
    val operations: Set<WorkspaceOperation>,
    val grantedAtEpochMs: Long
)

/** Narrow authority boundary for developer-facing workspace capabilities. */
interface WorkspaceAuthority {
    fun listGrants(): List<WorkspaceGrant>
    fun getGrant(id: String): WorkspaceGrant?
    fun registerGrant(grant: WorkspaceGrant)
    fun revokeGrant(id: String)
    fun resolveDocument(grantId: String, relativePath: String): ResolvedWorkspaceDocument
}

data class ResolvedWorkspaceDocument(
    val grantId: String,
    val relativePath: String,
    val documentUri: String,
    val isDirectory: Boolean,
    val displayName: String
)

class WorkspaceAccessException(message: String) : IllegalStateException(message)
