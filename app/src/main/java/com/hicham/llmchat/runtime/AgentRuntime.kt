package com.hicham.llmchat.runtime

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

class AgentRuntime(
    private val catalog: ActionCatalog,
    private val policy: PolicyEngine,
    private val capabilityExecutor: CapabilityExecutor,
    private val store: RuntimeStore = InMemoryRuntimeStore(),
    private val approvalStore: ApprovalStore = InMemoryApprovalStore()
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
            PolicyDecision.APPROVAL_REQUIRED -> requestApproval(request, action)
            PolicyDecision.ALLOW -> execute(request, action, action.plan(request.input))
        }
    }

    fun resolveApproval(
        runId: String,
        approvalId: String,
        decision: ApprovalDecision,
        approverIdentity: String
    ): Run? {
        val run = store.loadRun(runId, catalog) ?: return null
        if (run.status != RunStatus.WAITING_APPROVAL || run.approvalId != approvalId) return run
        val context = approvalStore.load(approvalId) ?: return run.copy(
            status = RunStatus.FAILED,
            denialReason = "Approval context not found"
        ).also(store::saveRun)
        val action = catalog.get(context.actionId) ?: return run.copy(
            status = RunStatus.FAILED,
            denialReason = "Approved Action no longer exists"
        ).also(store::saveRun)
        if (context.runId != runId || action.version != context.actionVersion || run.activation.identity != context.requesterIdentity || run.activation.input != context.input) {
            return run.copy(status = RunStatus.FAILED, denialReason = "Approval context conflict").also(store::saveRun)
        }
        val currentPlan = runCatching { action.plan(context.input) }.getOrElse {
            return run.copy(status = RunStatus.FAILED, denialReason = it.message ?: "Approval plan failed").also(store::saveRun)
        }
        if (fingerprint(context.requesterIdentity, action, currentPlan) != context.fingerprint || currentPlan != context.plannedInvocations) {
            return run.copy(status = RunStatus.FAILED, denialReason = "Approved operation no longer matches").also(store::saveRun)
        }
        if (decision == ApprovalDecision.DENIED) {
            return when (approvalStore.consume(approvalId, runId, context.requesterIdentity, context.fingerprint, decision, approverIdentity)) {
                ApprovalConsumption.CONSUMED -> run.copy(status = RunStatus.DENIED, denialReason = "Approval denied").also(store::saveRun)
                else -> run.copy(status = RunStatus.FAILED, denialReason = "Approval could not be consumed").also(store::saveRun)
            }
        }
        if (policy.evaluate(run.activation, action) == PolicyDecision.DENY) {
            return run.copy(status = RunStatus.DENIED, denialReason = "Policy denied Action after approval").also(store::saveRun)
        }
        return when (approvalStore.consume(approvalId, runId, context.requesterIdentity, context.fingerprint, decision, approverIdentity)) {
            ApprovalConsumption.CONSUMED -> execute(run.activation, action, currentPlan)
            ApprovalConsumption.NOT_PENDING -> run.copy(status = RunStatus.FAILED, denialReason = "Approval already consumed").also(store::saveRun)
            ApprovalConsumption.CONFLICT -> run.copy(status = RunStatus.FAILED, denialReason = "Approval context conflict").also(store::saveRun)
            ApprovalConsumption.NOT_FOUND -> run.copy(status = RunStatus.FAILED, denialReason = "Approval context not found").also(store::saveRun)
        }
    }

    private fun requestApproval(request: ActivationRequest, action: ActionDefinition): Run {
        val run = Run(activation = request, status = RunStatus.WAITING_APPROVAL, action = action)
        return try {
            val plan = action.plan(request.input)
            validatePlan(action, plan.invocations)
            val context = ApprovalContext(
                runId = run.id,
                requesterIdentity = request.identity,
                actionId = action.id,
                actionVersion = action.version,
                input = request.input,
                plannedInvocations = plan.invocations,
                fingerprint = fingerprint(request.identity, action, plan)
            )
            approvalStore.save(context)
            run.copy(approvalId = context.approvalId, denialReason = "Explicit approval required")
        } catch (e: Exception) {
            run.copy(status = RunStatus.FAILED, denialReason = e.message ?: "Approval planning failed")
        }.also(store::saveRun)
    }

    private fun execute(request: ActivationRequest, action: ActionDefinition, plan: ActionPlan): Run {
        val run = Run(activation = request, status = RunStatus.RUNNING, action = action)
        store.saveRun(run)
        var invocations = emptyList<CapabilityInvocation>()
        return try {
            invocations = materializeInvocations(run, action, request, plan.invocations)
            when (store.reserveEffects(invocations)) {
                EffectReservation.RESERVED -> Unit
                EffectReservation.REPLAY_BLOCKED -> return run.copy(status = RunStatus.FAILED, denialReason = "Effect replay blocked; reconciliation required").also(store::saveRun)
                EffectReservation.CONFLICT -> return run.copy(status = RunStatus.FAILED, denialReason = "Effect identity conflict; execution blocked").also(store::saveRun)
            }
            val capabilityExecutions = invocations.map(capabilityExecutor::execute)
            val execution = action.reduce(request.input, capabilityExecutions)
            val verification = Verification(execution.postcondition, if (execution.postcondition) "Postcondition satisfied" else "Postcondition failed")
            val evidence = Evidence(run.id, action.id, action.version, request.source, request.identity, invocations, capabilityExecutions.flatMap { it.observations } + execution.observations, verification)
            if (verification.passed) {
                invocations.forEach { store.completeEffect(it.effectId) }
                run.copy(status = RunStatus.SUCCEEDED, output = execution.output, evidence = evidence)
            } else {
                invocations.forEach { store.markEffectUnknown(it.effectId) }
                run.copy(status = RunStatus.FAILED, output = execution.output, evidence = evidence, denialReason = verification.reason)
            }.also(store::saveRun)
        } catch (e: Exception) {
            invocations.forEach { store.markEffectUnknown(it.effectId) }
            run.copy(status = RunStatus.FAILED, denialReason = e.message ?: "Action execution failed").also(store::saveRun)
        }
    }

    private fun validatePlan(action: ActionDefinition, specs: List<CapabilityInvocationSpec>) {
        specs.forEach { spec ->
            val descriptor = action.capabilities.firstOrNull { it.id == spec.capabilityId }
                ?: throw IllegalArgumentException("Action ${action.id} did not declare capability ${spec.capabilityId}")
            if (!descriptor.scope.containsAll(spec.scope)) throw IllegalArgumentException("Invocation scope exceeds declared scope for ${spec.capabilityId}")
        }
        val stable = specs.filter { !it.idempotencyKey.isNullOrBlank() }.map { "${it.capabilityId}|${it.idempotencyKey}" }
        if (stable.size != stable.distinct().size) throw IllegalArgumentException("Action plan contains duplicate effect identities")
    }

    private fun materializeInvocations(run: Run, action: ActionDefinition, request: ActivationRequest, specs: List<CapabilityInvocationSpec>): List<CapabilityInvocation> {
        validatePlan(action, specs)
        val invocations = specs.map { spec ->
            val descriptor = action.capabilities.first { it.id == spec.capabilityId }
            CapabilityInvocation(runId = run.id, capabilityId = descriptor.id, actionId = action.id, actionVersion = action.version, effectId = effectId(action, spec), scope = spec.scope, attributedTo = request.identity, parameters = spec.parameters)
        }
        if (invocations.map { it.effectId }.distinct().size != invocations.size) throw IllegalArgumentException("Action plan contains duplicate effect identities")
        return invocations
    }

    private fun effectId(action: ActionDefinition, spec: CapabilityInvocationSpec): String {
        val stableKey = spec.idempotencyKey
        return if (!stableKey.isNullOrBlank()) UUID.nameUUIDFromBytes("${action.id}:${action.version}:${spec.capabilityId}:$stableKey".toByteArray(StandardCharsets.UTF_8)).toString() else UUID.randomUUID().toString()
    }

    private fun fingerprint(identity: String, action: ActionDefinition, plan: ActionPlan): String {
        val canonical = buildString {
            append(identity).append('|').append(action.id).append('|').append(action.version).append('|')
            plan.invocations.forEach { spec ->
                append(spec.capabilityId).append(':').append(spec.scope.toList().sorted()).append(':')
                append(spec.parameters.toSortedMap()).append(':').append(spec.idempotencyKey ?: "").append(';')
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun missingAction(id: String) = ActionDefinition(id, 0, "missing", emptyList(), reduce = { _, _ -> error("missing action") })
}
