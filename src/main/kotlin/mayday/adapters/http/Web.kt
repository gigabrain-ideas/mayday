package mayday.adapters.http

import mayday.MaydayProperties
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.config.ResourceHandlerRegistry
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Serves the built web interface from MAYDAY_WEB_DIR or the classpath. Hashed assets are immutable. */
@Configuration(proxyBeanMethods = false)
class StaticFiles(private val props: MaydayProperties) : WebFluxConfigurer {
    val root: String = props.webDir.takeIf { it.isNotBlank() }?.let { "file:" + Path.of(it).toAbsolutePath().normalize() + "/" } ?: "classpath:/static/"

    fun index(): Resource = props.webDir.takeIf { it.isNotBlank() }?.let { FileSystemResource(Path.of(it).resolve("index.html")) } ?: ClassPathResource("static/index.html")

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/assets/**").addResourceLocations(root + "assets/")
            .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
        registry.addResourceHandler("/**").addResourceLocations(root).setCacheControl(CacheControl.noCache())
    }
}

/** Security headers, and the single-page-app fallback: unknown GET paths without a file extension get index.html. */
@Component
class WebFilters(private val static: StaticFiles) : WebFilter {
    private val reserved = listOf("/api/", "/auth/", "/oauth/", "/mcp", "/mayday.v1.", "/healthz", "/livez", "/readyz", "/metrics", "/grpc.")

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.pathWithinApplication().value()
        val h = exchange.response.headers
        h.set("X-Content-Type-Options", "nosniff")
        h.set("Referrer-Policy", "same-origin")
        val api = reserved.any { path.startsWith(it) }
        if (!api) {
            h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
            h.set("X-Frame-Options", "DENY")
        }
        val get = exchange.request.method == HttpMethod.GET || exchange.request.method == HttpMethod.HEAD
        if (!get || api || path.substringAfterLast('/').contains('.')) return chain.filter(exchange)
        if (!static.index().exists()) {
            exchange.response.headers.contentType = MediaType.TEXT_HTML
            val body = exchange.response.bufferFactory().wrap(PLACEHOLDER.toByteArray())
            return exchange.response.writeWith(Mono.just(body))
        }
        if (path == "/index.html") return chain.filter(exchange)
        return chain.filter(exchange.mutate().request(exchange.request.mutate().path("/index.html").build()).build())
    }

    companion object {
        const val PLACEHOLDER = "<!doctype html><title>Mayday</title><p style='font-family:monospace'>The web interface is not built. Run <code>make web</code>.</p>"
    }
}
