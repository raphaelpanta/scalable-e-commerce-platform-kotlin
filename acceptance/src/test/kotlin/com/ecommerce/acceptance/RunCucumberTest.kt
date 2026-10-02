package com.ecommerce.acceptance

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PUBLISH_QUIET_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * Runs every journey under `features/` against the platform at `GATEWAY_URL`. Scenarios are selected by tag with
 * the `cucumber.filter.tags` system property (forwarded by the Gradle `test` task), for example
 * `./gradlew -q :acceptance:test -Dcucumber.filter.tags="@us4 and not @slow"`; JUnit reads configuration
 * parameters from system properties, so no annotation is needed for it.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.ecommerce.acceptance.steps")
@ConfigurationParameter(key = PLUGIN_PUBLISH_QUIET_PROPERTY_NAME, value = "true")
class RunCucumberTest
