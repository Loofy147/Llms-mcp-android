package com.hicham.llmchat.runtime

/** Operations that a workspace grant can authorize. */
enum class WorkspaceOperation {
    READ,
    LIST,
    HASH
}

/**
 * Durable identity for a user-granted workspace.
 * The authority implementation owns the backing URI and must fail closed when access is lost.
 */
data class WorkspaceGrant(
    val id: String,
    val displayName: String,
    val authority: String,
    val treeUri: String,
    val operations: Set<WorkspaceOperation>,
    val grantedAtEpochMs: Long
)

/**
 * Narrow authority boundary for developer-facing workspace capabilities.
 * Capability implementations must never accept an arbitrary filesystem root instead.
 */
interface WorkspaceAuthority {
    fun listGrants(): List<WorkspaceGrant>

    fun getGrant(id: String): WorkspaceGrant?

    /**
     * Register a tree URI returned by Android's user-mediated document picker.
     * Implementations must persist only the authority needed to revalidate that grant later.
     */
    fun registerGrant(
        grant: WorkspaceGrant
    )

    /** Remove a grant. Subsequent access must fail closed. */
    fun revokeGrant(id: String)

    /** Resolve a normalized relative path inside a grant. */
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
