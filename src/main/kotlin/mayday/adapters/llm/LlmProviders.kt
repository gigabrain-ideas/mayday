package mayday.adapters.llm

import mayday.agents.GatewayAuth
import mayday.agents.LlmApi
import mayday.agents.LlmConfig
import mayday.agents.LlmProvider
import mayday.agents.LlmProviderFactory
import mayday.shared.Telemetry

/** Selects the adapter for the configured API protocol. Without telemetry no trace context goes to the gateway. */
object LlmProviders : LlmProviderFactory by TracedLlmProviders(Telemetry.noop, RetryPolicy.DEFAULT) {
    fun withTelemetry(telemetry: Telemetry, retry: RetryPolicy = RetryPolicy.DEFAULT): LlmProviderFactory = TracedLlmProviders(telemetry, retry)
}

private class TracedLlmProviders(private val telemetry: Telemetry, private val retry: RetryPolicy) : LlmProviderFactory {
    override fun create(config: LlmConfig, auth: GatewayAuth): LlmProvider = when (config.api) {
        LlmApi.ANTHROPIC -> AnthropicProvider(gatewayHttp, config, auth, telemetry, retry)
        LlmApi.OPENAI -> OpenAiProvider(gatewayHttp, config, auth, telemetry, retry)
    }
}
