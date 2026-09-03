package com.hicham.llmchat.runtime

import java.nio.charset.StandardCharsets
import java.util.UUID

class AgentRuntime(
    private val catalog: ActionCatalog,
    private val policy: PolicyEngine,
    private val store: RuntimeStore = InMemoryRuntimeStore()
) {
    fun activate(request: ActivationRequest): Run {
        val action = catalog.get(request.actionId)
            ?: return Run(
                activation = request,
                status = RunStatus.FAILED,
                action = missingAction(request.actionId),
                denialReason = "Unknown Action: ${request.actionId}"
            ).also(store::saveRun)

        return when (policy.evaluate(request, action)) {
            PolicyDecision.DENY -> Run(
                activation = request,
                status = RunStatus.DENIED,
                action = action,
                denialReason = "Policy denied Action"
            ).also(store::saveRun)
            PolicyDecision.APPROVAL_REQUIRED -> Run(
                activation = request,
                status = RunStatus.WAITING_APPROVAL,
                action = action,
                denialReason = "Explicit approval required"
            ).also(store::saveRun)
            PolicyDecision.ALLOW -> execute(request, action)
        }
    }

    private fun execute(request: ActivationRequest, action: ActionDefinition): Run {
        val run = Run(activation = request, status = RunStatus.RUNNING, action = action)
        store.saveRun(run)
        val invocations: List<CapabilityInvocation>
        return try {
            val plan = action.plan(request.input)
            invocations = materializeInvocations(run, action, request, plan.invocations)
            val reservation = store.reserveEffects(invocations)
            when (reservation) {
                EffectReservation.RESERVED -> Unit
                EffectReservation.REPLAY_BLOCKED -> {
                    return run.copy(
                        status = RunStatus.FAILED,
                        denialReason = "Effect replay blocked; reconciliation required"
                    ).also(store::saveRun)
                }
                EffectReservation.CONFLICT -> {
                    return run.copy(
                        status = RunStatus.FAILED,
                        denialReason = "Effect identity conflict; execution blocked"
                    ).also(store::saveRun)
                }
            }

            val execution = action.execute(request.input)
            if (execution.invocations != plan.invocations) {
                invocations.forEach { store.markEffectUnknown(it.effectId) }
                return run.copy(
                    status = RunStatus.FAILED,
                    denialReason = "Action execution changed its declared capability plan"
                ).also(store::saveRun)
            }

            val verification = Verification(
                passed = execution.postcondition,
                reason = if (execution.postcondition) "Postcondition satisfied" else "Postcondition failed"
            )
            val evidence = Evidence(
                runId = run.id,
                actionId = action.id,
                actionVersion = action.version,
                activationSource = request.source,
                authorizedBy = request.identity,
                capabilityInvocations = invocations,
                observations = execution.observations,
                verification = verification
            )
            if (verification.passed) {
                invocations.forEach { store.completeEffect(it.effectId) }
                run.copy(status = RunStatus.SUCCEEDED, output = execution.output, evidence = evidence)
            } else {
                invocations.forEach { store.markEffectUnknown(it.effectId) }
                run.copy(
                    status = RunStatus.FAILED,
                    output = execution.output,
                    evidence = evidence,
                    denialReason = verification.reason
                )
            }.also(store::saveRun)
        } catch (e: Exception) {
            if (::invocations.isInitialized) invocations.forEach { store.markEffectUnknown(it.effectId) }
            run.copy(status = RunStatus.FAILED, denialReason = e.message ?: "Action execution failed")
                .also(store::saveRun)
        }
    }

    private fun materializeInvocations(
        run: Run,
        action: ActionDefinition,
        request: ActivationRequest,
        specs: List<CapabilityInvocationSpec>
    ): List<CapabilityInvocation> {
        val invocations = specs.map { spec ->
            val descriptor = action.capabilities.firstOrNull { it.id == spec.capabilityId }
                ?: throw IllegalArgumentException("Action ${action.id} did not declare capability ${spec.capabilityId}")
            if (!descriptor.scope.containsAll(spec.scope)) {
                throw IllegalArgumentException("Invocation scope exceeds declared scope for ${spec.capabilityId}")
            }
            CapabilityInvocation(
                runId = run.id,
                capabilityId = descriptor.id,
                actionId = action.id,
                actionVersion = action.version,
                effectId = effectId(action, spec),
                scope = spec.scope,
                attributedTo = request.identity,
                parameters = spec.parameters
            )
        }
        if (invocations.map { it.effectId }.distinct().size != invocations.size) {
            throw IllegalArgumentException("Action plan contains duplicate effect identities")
        }
        return invocations
    }

    private fun effectId(action: ActionDefinition, spec: CapabilityInvocationSpec): String {
        val stableKey = spec.idempotencyKey
        return if (!stableKey.isNullOrBlank()) {
            UUID.nameUUIDFromBytes(
                "${action.id}:${action.version}:${spec.capabilityId}:$stableKey".toByteArray(StandardCharsets.UTF_8)
            ).toString()
        } else {
            UUID.randomUUID().toString()
        }
    }

    private fun missingAction(id: String) = ActionDefinition(
        id = id,
        version = 0,
        purpose = "missing",
        capabilities = emptyList()
    ) { error("missing action") }
}
