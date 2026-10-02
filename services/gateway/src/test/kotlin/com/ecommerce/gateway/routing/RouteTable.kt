package com.ecommerce.gateway.routing

import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.cloud.gateway.config.GatewayProperties
import org.springframework.cloud.gateway.route.RouteDefinition
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser

/** The route table of `application.yml`, bound exactly as Spring Boot binds it for Spring Cloud Gateway. */
class RouteTable private constructor(
    val properties: GatewayProperties,
) {
    val routes: List<RouteDefinition> get() = properties.routes

    /** The first route whose `Path` and `Method` predicates accept the request, as the gateway evaluates them. */
    fun match(
        method: String,
        path: String,
    ): RouteDefinition? = routes.firstOrNull { accepts(it, method, path) }

    private fun accepts(
        route: RouteDefinition,
        method: String,
        path: String,
    ): Boolean {
        val patterns = argumentsOf(route, "Path")
        val methods = argumentsOf(route, "Method")
        val container = PathContainer.parsePath(path)
        return method in methods && patterns.any { parser.parse(it).matches(container) }
    }

    private fun argumentsOf(
        route: RouteDefinition,
        predicate: String,
    ): List<String> =
        route.predicates
            .filter { it.name == predicate }
            .flatMap { it.args.values }
            .map(String::trim)

    companion object {
        private val parser = PathPatternParser()

        /** Loads `application.yml` from the classpath with the given environment overrides. */
        fun load(overrides: Map<String, Any> = emptyMap()): RouteTable {
            val environment = StandardEnvironment()
            environment.propertySources.addFirst(MapPropertySource("overrides", overrides))
            YamlPropertySourceLoader()
                .load("application.yml", ClassPathResource("application.yml"))
                .forEach(environment.propertySources::addLast)
            val properties =
                Binder
                    .get(environment)
                    .bind("spring.cloud.gateway.server.webflux", GatewayProperties::class.java)
                    .get()
            return RouteTable(properties)
        }
    }
}
