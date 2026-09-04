package com.hicham.llmchat.runtime

import java.net.URI

/** Data classes carried across a remote boundary. */
enum class EgressDataClass {
    USER_CONTENT,
    USER_CONFIGURATION,
    CREDENTIAL
}

data class EgressRequest(
    val destination: String,
    val purpose: String,
    val dataClasses: Set<EgressDataClass> = emptySet()
)

sealed interface EgressDecision {
    data object ALLOW : EgressDecision
    data class DENY(val reason: String) : EgressDecision
}

fun interface EgressPolicy {
    fun decide(request: EgressRequest): EgressDecision
}

/**
 * Minimal local egress boundary for the current provider architecture.
 * Only HTTPS destinations on the explicit allowlist are accepted.
 */
class AllowlistEgressPolicy(private val allowedHosts: Set<String>) : EgressPolicy {
    override fun decide(request: EgressRequest): EgressDecision {
        val uri = runCatching { URI(request.destination) }.getOrNull()
            ?: return EgressDecision.DENY("Invalid egress destination")

        if (uri.scheme?.lowercase() != "https") {
            return EgressDecision.DENY("Egress requires HTTPS")
        }

        val host = uri.host?.lowercase()
            ?: return EgressDecision.DENY("Egress destination has no host")

        if (uri.userInfo != null) {
            return EgressDecision.DENY("Egress destination must not embed credentials")
        }

        if (host !in allowedHosts.map(String::lowercase).toSet()) {
            return EgressDecision.DENY("Destination host is not allowed: $host")
        }

        return EgressDecision.ALLOW
    }
}
