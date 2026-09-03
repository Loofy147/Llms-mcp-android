package com.hicham.llmchat.runtime

class PolicyEngine(
    private val allowHighImpact: Boolean = false,
    private val requireApprovalForReversible: Boolean = false
) {
    fun evaluate(request: ActivationRequest, action: ActionDefinition): PolicyDecision {
        if (request.identity.isBlank()) return PolicyDecision.DENY

        if (action.capabilities.any { it.id.isBlank() }) {
            return PolicyDecision.DENY
        }

        if (action.capabilities.any { it.effect == EffectClass.HIGH_IMPACT } && !allowHighImpact) {
            return PolicyDecision.DENY
        }

        if (
            action.approvalMode == ApprovalMode.REQUIRED ||
            (requireApprovalForReversible && action.capabilities.any { it.effect == EffectClass.REVERSIBLE })
        ) {
            return PolicyDecision.APPROVAL_REQUIRED
        }

        return PolicyDecision.ALLOW
    }
}
