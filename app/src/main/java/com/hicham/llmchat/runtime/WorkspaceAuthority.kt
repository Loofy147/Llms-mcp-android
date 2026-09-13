package com.hicham.llmchat.runtime

/** Operations that a workspace grant can authorize. */
enum class WorkspaceOperation {
    READ,
    LIST,
    HASH
}

/** Durable identity for a user-granted Android document tree. */
data class WorkspaceGrant(
    val id: String,
    val displayName: String,
    val authority: String,
    val treeUri: String,
    val operations: Set<WorkspaceOperation>,
    val grantedAtEpochMs: Long
)

/**
 * Explicit authority boundary for developer-facing workspace capabilities.
 * Capability implementations must not accept arbitrary filesystem roots.
 */
interface WorkspaceAuthority {
    fun listGrants(): List<WorkspaceGrant>
    fun getGrant(id: String): WorkspaceGrant?
    fun registerGrant(grant: WorkspaceGrant)
    fun revokeGrant(id: String)
    fun resolveDocument(
        grantId: String,
        relativePath: String,
        operation: WorkspaceOperation
    ): ResolvedWorkspaceDocument
}

data class ResolvedWorkspaceDocument(
    val grantId: String,
    val relativePath: String,
    val documentUri: String,
    val isDirectory: Boolean,
    val displayName: String
)

class WorkspaceAccessException(message: String) : IllegalStateException(message)
