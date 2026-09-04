package com.hicham.llmchat.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class EgressPolicyTest {
    private val policy = AllowlistEgressPolicy(setOf("api.anthropic.com"))

    @Test
    fun allowsHttpsToExplicitProviderHost() {
        assertEquals(
            EgressDecision.ALLOW,
            policy.decide(
                EgressRequest(
                    destination = "https://api.anthropic.com/v1/messages",
                    purpose = "model inference",
                    dataClasses = setOf(EgressDataClass.USER_CONTENT, EgressDataClass.CREDENTIAL)
                )
            )
        )
    }

    @Test
    fun deniesUnlistedHost() {
        val decision = policy.decide(
            EgressRequest(
                destination = "https://evil.example.com/collect",
                purpose = "unexpected export",
                dataClasses = setOf(EgressDataClass.USER_CONTENT)
            )
        )
        assertEquals(EgressDecision.DENY("Destination host is not allowed: evil.example.com"), decision)
    }

    @Test
    fun deniesNonHttpsDestination() {
        val decision = policy.decide(
            EgressRequest(
                destination = "http://api.anthropic.com/v1/messages",
                purpose = "model inference"
            )
        )
        assertEquals(EgressDecision.DENY("Egress requires HTTPS"), decision)
    }

    @Test
    fun deniesEmbeddedCredentialsInDestination() {
        val decision = policy.decide(
            EgressRequest(
                destination = "https://user:password@api.anthropic.com/v1/messages",
                purpose = "model inference"
            )
        )
        assertEquals(EgressDecision.DENY("Egress destination must not embed credentials"), decision)
    }
}
