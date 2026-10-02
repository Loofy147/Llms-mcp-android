package com.hicham.llmchat.data

/**
 * Provider selection encoded in the persisted model field so existing settings remain compatible.
 *
 *   cscs:<model-id>    -> CSCS Inference / Apertus
 *   nebius:<model-id>  -> Nebius Token Factory
 *   anything else      -> existing Anthropic path
 */
data class ModelProviderRoute(
    val provider: Provider,
    val model: String
) {
    enum class Provider { ANTHROPIC, NEBIUS, CSCS }

    companion object {
        private const val CSCS_PREFIX = "cscs:"
        private const val NEBIUS_PREFIX = "nebius:"

        fun parse(rawModel: String): ModelProviderRoute {
            val normalized = rawModel.trim()

            if (normalized.startsWith(CSCS_PREFIX, ignoreCase = true)) {
                val model = normalized.substring(CSCS_PREFIX.length).trim()
                require(model.isNotBlank()) { "CSCS model id is empty" }
                return ModelProviderRoute(Provider.CSCS, model)
            }

            if (normalized.startsWith(NEBIUS_PREFIX, ignoreCase = true)) {
                val model = normalized.substring(NEBIUS_PREFIX.length).trim()
                require(model.isNotBlank()) { "Nebius model id is empty" }
                return ModelProviderRoute(Provider.NEBIUS, model)
            }

            return ModelProviderRoute(Provider.ANTHROPIC, normalized)
        }
    }
}
