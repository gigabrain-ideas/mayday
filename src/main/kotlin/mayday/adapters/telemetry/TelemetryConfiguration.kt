package mayday.adapters.telemetry

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.sdk.trace.samplers.SamplingResult
import mayday.VERSION
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsConnectionDetails
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingConnectionDetails
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.Transport
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Hooks
import java.util.logging.Logger

/**
 * Parent-based ratio sampling. A root span of an agent run is always sampled when [agentRuns] is true:
 * agent runs are few and operators need each of them during an incident.
 */
class MaydaySampler(ratio: Double, private val agentRuns: Boolean) : Sampler {
    private val delegate = Sampler.parentBased(Sampler.traceIdRatioBased(ratio.coerceIn(0.0, 1.0)))

    override fun shouldSample(parent: Context, traceId: String, name: String, kind: SpanKind, attributes: Attributes, links: List<LinkData>): SamplingResult {
        if (agentRuns && !Span.fromContext(parent).spanContext.isValid && (name.startsWith("invoke_agent") || name.startsWith("agent.dispatch"))) {
            return SamplingResult.recordAndSample()
        }
        return delegate.shouldSample(parent, traceId, name, kind, attributes, links)
    }

    override fun getDescription() = "MaydaySampler{agentRuns=$agentRuns,${delegate.description}}"
}

/** OpenTelemetry settings on top of Spring Boot's auto-configuration. See docs/observability.md. */
@Configuration(proxyBeanMethods = false)
class TelemetryConfiguration {
    @Bean
    fun maydayTracerProvider(
        @Value("\${management.tracing.sampling.probability:1.0}") ratio: Double,
        @Value("\${mayday.telemetry.sample-agent-runs:true}") agentRuns: Boolean,
        @Value("\${mayday.telemetry.environment:}") environment: String,
    ) = SdkTracerProviderBuilderCustomizer { builder ->
        val attrs = Attributes.builder().put(AttributeKey.stringKey("service.version"), VERSION)
        if (environment.isNotBlank()) attrs.put(AttributeKey.stringKey("deployment.environment"), environment.trim())
        builder.addResource(Resource.create(attrs.build()))
        builder.setSampler(MaydaySampler(ratio, agentRuns))
    }

    /** Libraries that use the global instance (for example the Anthropic SDK) share the SDK and its exporters. */
    @Bean
    fun globalOpenTelemetry(openTelemetry: OpenTelemetry) = InitializingBean {
        runCatching { GlobalOpenTelemetry.set(openTelemetry) }
            .onFailure { Logger.getLogger("mayday.telemetry").fine("GlobalOpenTelemetry is set already: ${it.message}") }
    }

    /**
     * Same as spring.reactor.context-propagation=auto: WebFlux handlers (also suspend handlers) see the server span,
     * so domain spans of an HTTP request nest below it.
     */
    @Bean
    fun reactorContextPropagation() = InitializingBean { Hooks.enableAutomaticContextPropagation() }

    /** MAYDAY_OTEL_ENDPOINT is the OTLP base URL; it has priority over OTEL_EXPORTER_OTLP_ENDPOINT. */
    @Bean
    @ConditionalOnExpression("!'\${mayday.telemetry.endpoint:}'.isBlank()")
    fun maydayOtlpTracing(@Value("\${mayday.telemetry.endpoint}") endpoint: String) = object : OtlpTracingConnectionDetails {
        override fun getUrl(transport: Transport) = signalUrl(endpoint, transport == Transport.HTTP, "traces")
    }

    @Bean
    @ConditionalOnExpression("!'\${mayday.telemetry.endpoint:}'.isBlank()")
    fun maydayOtlpMetrics(@Value("\${mayday.telemetry.endpoint}") endpoint: String) = object : OtlpMetricsConnectionDetails {
        override fun getUrl() = signalUrl(endpoint, true, "metrics")
    }

    companion object {
        fun signalUrl(endpoint: String, http: Boolean, signal: String): String {
            val base = endpoint.trim().trimEnd('/')
            return if (!http || base.endsWith("/v1/$signal")) base else "$base/v1/$signal"
        }
    }
}
