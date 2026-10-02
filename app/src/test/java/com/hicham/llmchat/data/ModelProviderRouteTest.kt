package com.hicham.llmchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelProviderRouteTest {
    @Test
    fun plainModelUsesAnthropicProvider() {
        assertEquals(
            ModelProviderRoute(
                ModelProviderRoute.Provider.ANTHROPIC,
                "claude-sonnet-5"
            ),
            ModelProviderRoute.parse("claude-sonnet-5")
        )
    }

    @Test
    fun nebiusPrefixSelectsNebiusWithoutChangingModelId() {
        assertEquals(
            ModelProviderRoute(
                ModelProviderRoute.Provider.NEBIUS,
                "nvidia/nvidia-nemotron-3-nano-30b-a3b"
            ),
            ModelProviderRoute.parse(
                "nebius:nvidia/nvidia-nemotron-3-nano-30b-a3b"
            )
        )
    }

    @Test
    fun blankNebiusModelIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelProviderRoute.parse("nebius:")
        }
    }
}
