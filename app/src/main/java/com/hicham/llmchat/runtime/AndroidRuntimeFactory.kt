package com.hicham.llmchat.runtime

import android.content.Context
import android.net.Uri
import java.io.File

/** Single application composition root for the local control plane. */
object AndroidRuntimeFactory {
    @Volatile
    private var recoveryPerformedInProcess = false

    @Synchronized
    private fun recoverOncePerProcess(store: RuntimeStore) {
        if (recoveryPerformedInProcess) return
        store.recoverInterruptedEffects()
        recoveryPerformedInProcess = true
    }

    fun create(context: Context): AgentRuntime {
        val app = context.applicationContext
        val runtimeDir = File(app.filesDir, "agent-runtime")
        runtimeDir.mkdirs()
        val workspaceAuthority = AndroidWorkspaceAuthority(app)

        val capabilityExecutor = RegistryCapabilityExecutor(
            mapOf(
                "device.time.read" to { NativeActions.timeNow() },
                "device.calculator.evaluate" to { invocation ->
                    val expression = invocation.parameters["expression"].orEmpty()
                    val value = SafeArithmetic.evaluate(expression)
                    CapabilityExecution(
                        output = mapOf("result" to value.toString()),
                        observations = listOf(Observation("result", value.toString()))
                    )
                },
                DeveloperActions.WORKSPACE_FILE_READ to { invocation ->
                    val grantId = invocation.parameters["grant_id"].orEmpty()
                    val path = invocation.parameters["path"].orEmpty()
                    val resolved = workspaceAuthority.resolveDocument(
                        grantId = grantId,
                        relativePath = path,
                        operation = WorkspaceOperation.READ
                    )
                    if (resolved.isDirectory) {
                        throw WorkspaceAccessException("dev.file.read requires a file, not a directory")
                    }
                    val inputStream = app.contentResolver.openInputStream(Uri.parse(resolved.documentUri))
                        ?: throw WorkspaceAccessException("Workspace file is unavailable")
                    DeveloperActions.readText(inputStream, resolved.relativePath)
                },
                DeveloperHashAction.WORKSPACE_FILE_HASH to { invocation ->
                    val grantId = invocation.parameters["grant_id"].orEmpty()
                    val path = invocation.parameters["path"].orEmpty()
                    val resolved = workspaceAuthority.resolveDocument(
                        grantId = grantId,
                        relativePath = path,
                        operation = WorkspaceOperation.HASH
                    )
                    if (resolved.isDirectory) {
                        throw WorkspaceAccessException("dev.file.hash requires a file, not a directory")
                    }
                    val inputStream = app.contentResolver.openInputStream(Uri.parse(resolved.documentUri))
                        ?: throw WorkspaceAccessException("Workspace file is unavailable")
                    DeveloperHashAction.hash(inputStream, resolved.relativePath)
                }
            )
        )

        val runtimeStore = JournalRuntimeStore(File(runtimeDir, "runtime.journal"))
        recoverOncePerProcess(runtimeStore)

        return AgentRuntime(
            catalog = ActionCatalog(
                NativeActions.catalog() +
                    DeveloperActions.catalog() +
                    DeveloperHashAction.catalog()
            ),
            policy = PolicyEngine(),
            capabilityExecutor = capabilityExecutor,
            store = runtimeStore,
            approvalStore = JournalApprovalStore(File(runtimeDir, "approvals.journal"))
        )
    }
}
