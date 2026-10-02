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
    fun geminiPrefixSelectsGeminiWithoutChangingModelId() {
        assertEquals(
            ModelProviderRoute(
                ModelProviderRoute.Provider.GEMINI,
                "gemini-3.8-flash"
            ),
            ModelProviderRoute.parse("gemini:gemini-3.8-flash")
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
    fun cscsPrefixSelectsCscsWithoutChangingModelId() {
        assertEquals(
            ModelProviderRoute(
                ModelProviderRoute.Provider.CSCS,
                "swiss-ai/Apertus-v1.5-8B"
            ),
            ModelProviderRoute.parse("cscs:swiss-ai/Apertus-v1.5-8B")
        )
    }

    @Test
    fun blankGeminiModelIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelProviderRoute.parse("gemini:")
        }
    }

    @Test
    fun blankNebiusModelIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelProviderRoute.parse("nebius:")
        }
    }

    @Test
    fun blankCscsModelIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelProviderRoute.parse("cscs:")
        }
    }
}
