package com.hicham.llmchat.runtime

/**
 * The only runtime-owned interface allowed to perform a Capability effect.
 * Implementations are trusted adapters to Android, filesystem, network, MCP, or other domains.
 */
fun interface CapabilityExecutor {
    fun execute(invocation: CapabilityInvocation): CapabilityExecution
}

/**
 * Small deterministic executor registry used by the core and tests.
 * Unknown capabilities fail closed instead of falling back to Action code.
 */
class RegistryCapabilityExecutor(
    private val handlers: Map<String, (CapabilityInvocation) -> CapabilityExecution>
) : CapabilityExecutor {
    override fun execute(invocation: CapabilityInvocation): CapabilityExecution =
        handlers[invocation.capabilityId]?.invoke(invocation)
            ?: throw IllegalArgumentException("No executor registered for ${invocation.capabilityId}")
}
