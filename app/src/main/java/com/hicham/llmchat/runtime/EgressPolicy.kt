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
 * Local egress boundary for the provider architecture.
 * Destination and data-class admission are independently enforced.
 * Content minimization/redaction remains a separate layer.
 */
class AllowlistEgressPolicy(
    private val allowedHosts: Set<String>,
    private val allowedDataClasses: Set<EgressDataClass> = EgressDataClass.entries.toSet()
) : EgressPolicy {
    private val normalizedHosts = allowedHosts.map(String::lowercase).toSet()

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

        if (host !in normalizedHosts) {
            return EgressDecision.DENY("Destination host is not allowed: $host")
        }

        val disallowedDataClasses = request.dataClasses - allowedDataClasses
        if (disallowedDataClasses.isNotEmpty()) {
            return EgressDecision.DENY(
                "Egress data classes are not allowed: ${disallowedDataClasses.toList().sortedBy { it.name }}"
            )
        }

        return EgressDecision.ALLOW
    }
}
