package com.hicham.llmchat.runtime

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android implementation backed by persistable Storage Access Framework tree grants.
 * It never treats a raw filesystem path as authority.
 */
class AndroidWorkspaceAuthority(context: Context) : WorkspaceAuthority {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun listGrants(): List<WorkspaceGrant> = readGrants()

    override fun getGrant(id: String): WorkspaceGrant? = readGrants().firstOrNull { it.id == id }

    override fun registerGrant(grant: WorkspaceGrant) {
        val treeUri = Uri.parse(grant.treeUri)
        val flags = ContentResolver.FLAG_GRANT_READ_URI_PERMISSION or
            ContentResolver.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(treeUri, flags) }
            .onFailure {
                throw WorkspaceAccessException("Workspace permission could not be persisted")
            }

        val grants = readGrants().filterNot { it.id == grant.id } + grant
        writeGrants(grants)
    }

    override fun revokeGrant(id: String) {
        val grant = getGrant(id) ?: return
        val uri = Uri.parse(grant.treeUri)
        runCatching {
            resolver.releasePersistableUriPermission(uri, ContentResolver.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            resolver.releasePersistableUriPermission(uri, ContentResolver.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        writeGrants(readGrants().filterNot { it.id == id })
    }

    override fun resolveDocument(grantId: String, relativePath: String): ResolvedWorkspaceDocument {
        val grant = getGrant(grantId) ?: throw WorkspaceAccessException("Workspace grant not found")
        if (WorkspaceOperation.READ !in grant.operations &&
            WorkspaceOperation.LIST !in grant.operations &&
            WorkspaceOperation.HASH !in grant.operations
        ) {
            throw WorkspaceAccessException("Workspace has no read/list/hash authority")
        }

        val normalized = normalizeRelativePath(relativePath)
        val treeUri = Uri.parse(grant.treeUri)
        ensurePersistedReadPermission(treeUri)

        if (normalized.isEmpty()) {
            val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
            return ResolvedWorkspaceDocument(
                grantId = grant.id,
                relativePath = "",
                documentUri = rootUri.toString(),
                isDirectory = true,
                displayName = grant.displayName
            )
        }

        var parentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        var lastName = ""
        normalized.split('/').forEach { segment ->
            lastName = segment
            parentUri = findChildUri(parentUri, segment)
                ?: throw WorkspaceAccessException("Workspace path does not exist: $normalized")
        }

        val isDirectory = isDirectory(parentUri)
        return ResolvedWorkspaceDocument(
            grantId = grant.id,
            relativePath = normalized,
            documentUri = parentUri.toString(),
            isDirectory = isDirectory,
            displayName = lastName
        )
    }

    private fun findChildUri(parentUri: Uri, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            parentUri,
            DocumentsContract.getDocumentId(parentUri)
        )
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} = ?",
            arrayOf(name),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getString(0)
                return DocumentsContract.buildDocumentUriUsingTree(parentUri, id)
            }
        } ?: throw WorkspaceAccessException("Workspace authority is unavailable")
        return null
    }

    private fun isDirectory(uri: Uri): Boolean {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
            }
        } ?: throw WorkspaceAccessException("Workspace authority is unavailable")
        throw WorkspaceAccessException("Workspace document is unavailable")
    }

    private fun ensurePersistedReadPermission(uri: Uri) {
        val granted = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!granted) {
            throw WorkspaceAccessException("Workspace permission is unavailable or revoked")
        }
    }

    private fun normalizeRelativePath(input: String): String {
        val normalizedSeparators = input.replace('\\', '/')
        if (normalizedSeparators.startsWith('/') || normalizedSeparators.contains('\u0000')) {
            throw WorkspaceAccessException("Workspace path must be relative")
        }
        val segments = normalizedSeparators.split('/')
            .filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) {
            throw WorkspaceAccessException("Workspace path traversal is not allowed")
        }
        return segments.joinToString("/")
    }

    private fun readGrants(): List<WorkspaceGrant> {
        val raw = preferences.getString(KEY_GRANTS, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList(json.length()) {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    val operations = buildSet {
                        val values = item.getJSONArray("operations")
                        for (operationIndex in 0 until values.length()) {
                            add(WorkspaceOperation.valueOf(values.getString(operationIndex)))
                        }
                    }
                    add(
                        WorkspaceGrant(
                            id = item.getString("id"),
                            displayName = item.getString("displayName"),
                            authority = item.getString("authority"),
                            treeUri = item.getString("treeUri"),
                            operations = operations,
                            grantedAtEpochMs = item.getLong("grantedAtEpochMs")
                        )
                    )
                }
            }
        }.getOrElse {
            throw WorkspaceAccessException("Stored workspace grants are invalid")
        }
    }

    private fun writeGrants(grants: List<WorkspaceGrant>) {
        val json = JSONArray()
        grants.forEach { grant ->
            json.put(
                JSONObject().apply {
                    put("id", grant.id)
                    put("displayName", grant.displayName)
                    put("authority", grant.authority)
                    put("treeUri", grant.treeUri)
                    put("grantedAtEpochMs", grant.grantedAtEpochMs)
                    put("operations", JSONArray(grant.operations.map { it.name }))
                }
            )
        }
        preferences.edit().putString(KEY_GRANTS, json.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "workspace_authority"
        private const val KEY_GRANTS = "grants"
    }
}
