package com.hicham.llmchat.runtime

class ActionCatalog(actions: List<ActionDefinition>) {
    private val byId = actions.associateBy { it.id }

    fun get(actionId: String): ActionDefinition? = byId[actionId]

    fun all(): List<ActionDefinition> = byId.values.toList()

    companion object {
        fun demo(): ActionCatalog = ActionCatalog(
            listOf(
                ActionDefinition(
                    id = "local_status",
                    version = 1,
                    purpose = "Return a deterministic local runtime status without model inference.",
                    capabilities = listOf(
                        CapabilityDescriptor("runtime.status.read", EffectClass.READ_ONLY)
                    ),
                    execute = {
                        ActionExecution(
                            output = mapOf("status" to "ok"),
                            observations = listOf(Observation("status", "ok"))
                        )
                    },
                    plan = {
                        ActionPlan(
                            invocations = listOf(
                                CapabilityInvocationSpec("runtime.status.read")
                            )
                        )
                    }
                )
            )
        )
    }
}
