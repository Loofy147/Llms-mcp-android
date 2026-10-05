package com.hicham.llmchat.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class StaleResultOrderingTest {
    private fun action() = ActionDefinition(
        id = "stale-ordering",
        version = 1,
        purpose = "B7",
        capabilities = emptyList(),
        reduce = { _, _ -> ActionExecution() },
    )

    private fun run(
        id: String,
        status: RunStatus,
        value: String,
    ) = Run(
        id = id,
        activation = ActivationRequest(
            source = ActivationSource.EXTERNAL,
            actionId = "stale-ordering",
            identity = "b7-test",
        ),
        status = status,
        action = action(),
        output = mapOf("value" to value),
    )

    @Test
    fun lateOlderResultMustNotRegressNewerTerminalRun() {
        val journal = File.createTempFile("b7-stale", ".journal")
        journal.deleteOnExit()

        val store = JournalRuntimeStore(journal)
        val runId = "run-b7"
        val newer = run(runId, RunStatus.SUCCEEDED, "new")
        val older = run(runId, RunStatus.FAILED, "old")

        store.saveRun(newer)
        store.saveRun(older)

        val loaded = JournalRuntimeStore(journal).loadRun(runId, ActionCatalog(listOf(action())))
        assertEquals(
            "Late older result must not become authoritative over newer terminal state",
            "new",
            loaded?.output?.get("value"),
        )
        assertEquals(RunStatus.SUCCEEDED, loaded?.status)
    }
}
