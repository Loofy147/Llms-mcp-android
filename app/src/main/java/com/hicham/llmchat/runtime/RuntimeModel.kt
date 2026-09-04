package com.hicham.llmchat.runtime

import java.util.UUID

enum class ActivationSource { USER_UI, QUICK_ACTION, WIDGET, NOTIFICATION, AUTOMATION, EXTERNAL, MODEL }
enum class EffectClass { READ_ONLY, REVERSIBLE, HIGH_IMPACT }
enum class ApprovalMode { NEVER, REQUIRED }
enum class PolicyDecision { ALLOW, DENY, APPROVAL_REQUIRED }
enum class RunStatus { CREATED, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, DENIED, CANCELLED }

data class ActivationRequest(
    val source: ActivationSource,
    val actionId: String,
    val input: Map<String, String> = emptyMap(),
    val identity: String = "local-user"
)

data class CapabilityDescriptor(
    val id: String,
    val effect: EffectClass,
    val scope: Set<String> = emptySet()
)

data class CapabilityInvocation(
    val id: String = UUID.randomUUID().toString(),
    val runId: String,
    val capabilityId: String,
    val actionId: String,
    val actionVersion: Int,
    val effectId: String = UUID.randomUUID().toString(),
    val scope: Set<String> = emptySet(),
    val attributedTo: String,
    val parameters: Map<String, String> = emptyMap()
)

data class CapabilityInvocationSpec(
    val capabilityId: String,
    val scope: Set<String> = emptySet(),
    val parameters: Map<String, String> = emptyMap(),
    val idempotencyKey: String? = null
)

data class ActionPlan(val invocations: List<CapabilityInvocationSpec> = emptyList())

data class CapabilityExecution(
    val output: Map<String, String> = emptyMap(),
    val observations: List<Observation> = emptyList(),
    val postcondition: Boolean = true
)

/**
 * Action evaluation is deliberately effect-free. Capability side effects belong to CapabilityExecutor.
 */
data class ActionDefinition(
    val id: String,
    val version: Int,
    val purpose: String,
    val capabilities: List<CapabilityDescriptor>,
    val approvalMode: ApprovalMode = ApprovalMode.NEVER,
    val reduce: (Map<String, String>, List<CapabilityExecution>) -> ActionExecution,
    val plan: (Map<String, String>) -> ActionPlan = { ActionPlan() }
)

data class ActionExecution(
    val output: Map<String, String> = emptyMap(),
    val observations: List<Observation> = emptyList(),
    val postcondition: Boolean = true
)

data class Observation(val key: String, val value: String)
data class Verification(val passed: Boolean, val reason: String)

data class Evidence(
    val runId: String,
    val actionId: String,
    val actionVersion: Int,
    val activationSource: ActivationSource,
    val authorizedBy: String?,
    val capabilityInvocations: List<CapabilityInvocation>,
    val observations: List<Observation>,
    val verification: Verification
)

data class Run(
    val id: String = UUID.randomUUID().toString(),
    val activation: ActivationRequest,
    val status: RunStatus,
    val action: ActionDefinition,
    val output: Map<String, String> = emptyMap(),
    val evidence: Evidence? = null,
    val denialReason: String? = null
)
