package com.ecommerce.gateway.routing

import org.springframework.cloud.gateway.handler.predicate.AbstractRoutePredicateFactory
import org.springframework.cloud.gateway.support.ShortcutConfigurable.ShortcutType
import org.springframework.http.server.PathContainer
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import java.util.function.Predicate

/**
 * `NotPath=<pattern>,<pattern>,...`: the route matches only paths outside every listed pattern. Used by the storefront
 * catch-all (feature 005 gateway-routes.md), which excludes the API, management and discovery prefixes, so that an
 * unknown path below them still answers 404 `not-found` and is never served the SPA shell. Patterns are Spring path
 * patterns, as in `Path`.
 */
@Component
class NotPathRoutePredicateFactory :
    AbstractRoutePredicateFactory<NotPathRoutePredicateFactory.Config>(Config::class.java) {
    class Config {
        var patterns: List<String> = emptyList()
    }

    override fun shortcutFieldOrder(): List<String> = listOf(PATTERNS)

    override fun shortcutType(): ShortcutType = ShortcutType.GATHER_LIST

    override fun apply(config: Config): Predicate<ServerWebExchange> {
        val excluded = config.patterns.map(String::trim).map(parser::parse)
        return Predicate { exchange ->
            excludes(
                excluded,
                exchange.request.path
                    .pathWithinApplication()
                    .value(),
            )
        }
    }

    companion object {
        private const val PATTERNS = "patterns"
        private val parser = PathPatternParser()

        /** True when [path] matches none of [excluded]. */
        fun excludes(
            excluded: List<PathPattern>,
            path: String,
        ): Boolean = excluded.none { it.matches(PathContainer.parsePath(path)) }
    }
}
