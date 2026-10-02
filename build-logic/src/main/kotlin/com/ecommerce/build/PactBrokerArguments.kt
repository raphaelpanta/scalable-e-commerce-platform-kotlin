package com.ecommerce.build

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.process.CommandLineArgumentProvider

/**
 * Pact broker system properties of the contract layer, resolved when the test JVM starts (configuration-cache
 * safe). Without a broker URL only `pact.verifier.publishResults=false`; with one also `pactbroker.url`, the
 * optional credentials and `pact.provider.version`, and results are published only when [publishResults] is
 * true. Credentials are not task inputs, so they never reach a cache key.
 */
abstract class PactBrokerArguments : CommandLineArgumentProvider {
    @get:Input
    @get:Optional
    abstract val brokerUrl: Property<String>

    @get:Internal
    abstract val token: Property<String>

    @get:Internal
    abstract val username: Property<String>

    @get:Internal
    abstract val password: Property<String>

    @get:Input
    @get:Optional
    abstract val providerVersion: Property<String>

    @get:Input
    abstract val publishResults: Property<Boolean>

    override fun asArguments(): Iterable<String> {
        val url = brokerUrl.orNull
        val broker =
            if (url == null) {
                emptyList()
            } else {
                listOfNotNull(
                    "pactbroker.url" to url,
                    token.orNull?.let { "pactbroker.auth.token" to it },
                    username.orNull?.let { "pactbroker.auth.username" to it },
                    password.orNull?.let { "pactbroker.auth.password" to it },
                    providerVersion.orNull?.let { "pact.provider.version" to it },
                )
            }
        val publish = "pact.verifier.publishResults" to (url != null && publishResults.get()).toString()
        return (broker + publish).map { (key, value) -> "-D$key=$value" }
    }
}
