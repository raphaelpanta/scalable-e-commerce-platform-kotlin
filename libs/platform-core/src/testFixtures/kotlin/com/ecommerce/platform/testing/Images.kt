package com.ecommerce.platform.testing

/**
 * Container images of every Testcontainers-based test layer (service conventions section 5), pinned in one place.
 * WireMock runs in-process (`wiremock-standalone`), so it needs no image.
 */
object Images {
    const val POSTGRES: String = "postgres:18-alpine"
    const val KAFKA: String = "apache/kafka:4.1.1"
    const val MAILPIT: String = "axllent/mailpit:v1.31.3"
}

/** The internal API token used by tests and pacts (`INTERNAL_API_TOKEN`); never a real secret. */
object InternalToken {
    const val TEST: String = "pact-internal-token"

    /** Header that carries it. */
    const val HEADER: String = "X-Internal-Token"
}
