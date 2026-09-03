package com.hicham.llmchat.runtime

class ActionCatalog(actions: List<ActionDefinition>) {
    private val byId = actions.associateBy { it.id }

    fun get(actionId: String): ActionDefinition? = byId[actionId]

    fun all(): List<ActionDefinition> = byId.values.toList()

    companion object {
        fun demo(): ActionCatalog = ActionCatalog(
            listOf(
                ActionDefinition(
                    id = "calculate",
                    version = 1,
                    purpose = "Evaluate a basic arithmetic expression without model inference.",
                    capabilities = listOf(
                        CapabilityDescriptor("calculator.evaluate", EffectClass.READ_ONLY)
                    )
                ) { input ->
                    val expression = input["expression"] ?: error("Missing expression")
                    val result = com.hicham.llmchat.data.SafeArithmetic.evaluate(expression)
                    ActionExecution(
                        output = mapOf("result" to result.toString()),
                        observations = listOf(Observation("expression", expression), Observation("result", result.toString())),
                        postcondition = result.isFinite()
                    )
                },
                ActionDefinition(
                    id = "get_current_time",
                    version = 1,
                    purpose = "Read the device local time.",
                    capabilities = listOf(
                        CapabilityDescriptor("device.clock.read", EffectClass.READ_ONLY)
                    )
                ) {
                    val value = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss (zzz)",
                        java.util.Locale.getDefault()
                    ).format(java.util.Date())
                    ActionExecution(
                        output = mapOf("time" to value),
                        observations = listOf(Observation("local_time", value)),
                        postcondition = value.isNotBlank()
                    )
                }
            )
        )
    }
}
