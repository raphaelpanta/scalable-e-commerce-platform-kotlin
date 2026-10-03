package com.ecommerce.build

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.process.CommandLineArgumentProvider

/**
 * Pact broker system properties of the contract layer, resolved when the test JVM starts (configuration-cache
 * safe). Without a broker URL only `pact.verifier.publishResults=false`; with one also `pactbroker.url`, the
 * optional credentials, `pact.provider.version`, the provider branch ([providerBranch]: `pact.provider.branch` for
 * the published results, `pactbroker.providerBranch` for the `matchingBranch` selector) and, for a run triggered by a
 * broker webhook, the one pact to verify ([pactUrl]: `pact.filter.pacturl`, honoured by classes annotated
 * `@AllowOverridePactUrl`; [consumer]: `pact.filter.consumers`). Results are published only when [publishResults]
 * is true. Credentials are not task inputs, so they never reach a cache key.
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
    @get:Optional
    abstract val providerBranch: Property<String>

    @get:Input
    @get:Optional
    abstract val pactUrl: Property<String>

    @get:Input
    @get:Optional
    abstract val consumer: Property<String>

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
                    providerBranch.orNull?.let { "pact.provider.branch" to it },
                    providerBranch.orNull?.let { "pactbroker.providerBranch" to it },
                    pactUrl.orNull?.let { "pact.filter.pacturl" to it },
                    consumer.orNull?.let { "pact.filter.consumers" to it },
                )
            }
        val publish = "pact.verifier.publishResults" to (url != null && publishResults.get()).toString()
        return (broker + publish).map { (key, value) -> "-D$key=$value" }
    }
}
