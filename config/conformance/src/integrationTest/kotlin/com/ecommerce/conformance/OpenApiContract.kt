package com.ecommerce.conformance

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.parser.OpenAPIV3Parser
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.json.JsonMapper
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Conformance of a service's public operations to its OpenAPI file, `contracts/openapi/<context>.yaml` (pact-matrix
 * rule 3, constitution Principle V). Every exchange the integration test routes through [check] is validated with the
 * Atlassian OpenAPI validator: the response must match the documented status, media type, headers and body schema of
 * its operation; the request must match the contract whenever the service accepted it (2xx), while a refused request
 * (4xx) may break the contract on purpose. [verify] then fails for every violation and for every documented
 * (operation, status) pair that no exchange produced and that is not deferred with a reason, so an operation added to
 * the contract without a test, or an untested error status, fails the build.
 *
 * Shared by the integration layer of every service module (`config/conformance`, added by the `kotlin-service`
 * convention); the file is resolved from the module directory, the working directory of Gradle's test tasks.
 */
class OpenApiContract private constructor(
    private val file: Path,
) {
    private val validator: OpenApiInteractionValidator =
        OpenApiInteractionValidator
            .createForSpecificationUrl(file.toUri().toString())
            .withLevelResolver(LEVELS)
            .build()
    private val operations: List<Operation> = documentedOperations(file)
    private val exercised: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val violations: MutableList<String> = mutableListOf()

    /**
     * Validates the exchange of [response] against the contract, records its (operation, status) and returns the JSON
     * object body (empty when there is none). Fails at once when the status is not [status], since the following steps
     * of a test usually depend on it.
     */
    fun check(
        response: WebTestClient.ResponseSpec,
        status: Int,
    ): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (exchange(response, status) as? Map<String, Any?>).orEmpty()
    }

    /** [check] for an operation whose body is a JSON array. */
    fun checkList(
        response: WebTestClient.ResponseSpec,
        status: Int,
    ): List<Any?> = (exchange(response, status) as? List<*>).orEmpty()

    private fun exchange(
        response: WebTestClient.ResponseSpec,
        status: Int,
    ): Any? {
        val result = response.expectBody(ByteArray::class.java).returnResult()
        val actual = result.status.value()
        val path = result.url.rawPath
        val label = "${result.method} $path"
        val body = result.responseBody?.takeIf { it.isNotEmpty() }
        if (actual != status) {
            throw AssertionError("$label answered $actual, expected $status: ${body?.decodeToString()}")
        }
        val operation = operations.firstOrNull { it.matches(result.method.name(), path) }
        if (operation == null) {
            violations += "$label matches no operation of ${file.fileName}"
            return decode(body)
        }
        exercised += "${operation.id} $actual"
        val requestReport = validator.validateRequest(request(result))
        val responseReport = validator.validateResponse(path, operation.method, response(result))
        if (actual in SUCCESS && requestReport.hasErrors()) {
            violations += "${operation.id} $actual accepted a request the contract rejects:${describe(requestReport)}"
        }
        if (responseReport.hasErrors()) {
            violations += "${operation.id} $actual response does not conform:${describe(responseReport)}"
        }
        return decode(body)
    }

    /**
     * Fails listing every violation [check] recorded and every documented (operation, status) pair no exchange
     * produced, unless [deferred] names it, as `"<operationId> <status>"` or `"* <status>"`, with the reason it
     * cannot be exercised in this layer. A deferred pair that was exercised fails too, so the list stays honest.
     */
    fun verify(deferred: Map<String, String>) {
        val documented = operations.flatMap { op -> op.statuses.map { "${op.id} $it" } }
        val isDeferred = { pair: String -> pair in deferred || "* ${pair.substringAfter(' ')}" in deferred }
        val missing = documented.filterNot { it in exercised || isDeferred(it) }
        val stale = deferred.keys.filter { !it.startsWith("* ") && it in exercised }
        val unknown = deferred.keys.filter { !it.startsWith("* ") && it !in documented }
        val problems =
            violations.map { "violation: $it" } +
                missing.map { "not exercised: $it" } +
                stale.map { "deferred but exercised: $it" } +
                unknown.map { "deferred but not documented: $it" }
        if (problems.isNotEmpty()) {
            throw AssertionError(
                "${file.fileName}: ${problems.size} conformance problem(s)\n" + problems.joinToString("\n"),
            )
        }
    }

    private fun request(result: EntityExchangeResult<ByteArray>): Request {
        val url = result.url
        val builder = SimpleRequest.Builder(result.method.name(), url.rawPath)
        url.rawQuery
            ?.split('&')
            ?.filter(String::isNotEmpty)
            ?.map { it.split('=', limit = 2) }
            ?.groupBy({ decodeQuery(it[0]) }, { decodeQuery(it.getOrElse(1) { "" }) })
            ?.forEach { (name, values) -> builder.withQueryParam(name, values) }
        result.requestHeaders.copy(builder::withHeader)
        result.requestBodyContent?.takeIf { it.isNotEmpty() }?.let(builder::withBody)
        return builder.build()
    }

    private fun response(result: EntityExchangeResult<ByteArray>): SimpleResponse {
        val builder = SimpleResponse.Builder(result.status.value())
        result.responseHeaders.copy(builder::withHeader)
        result.responseBody?.takeIf { it.isNotEmpty() }?.let(builder::withBody)
        return builder.build()
    }

    /** One documented operation: its id, method, path template and response statuses. */
    private class Operation(
        val id: String,
        val method: Request.Method,
        val template: String,
        val statuses: List<String>,
    ) {
        private val pattern: Regex = Regex("^" + template.replace(Regex("\\{[^/}]+}"), "[^/]+") + "$")
        private val variables: Int = template.count { it == '{' }

        fun matches(
            requestMethod: String,
            path: String,
        ): Boolean = method.name == requestMethod && pattern.matches(path)

        companion object {
            /** Literal segments win over variables: `/a/b` before `/a/{id}`. */
            val SPECIFIC_FIRST: Comparator<Operation> = compareBy { it.variables }
        }
    }

    companion object {
        private val SUCCESS: IntRange = 200..299

        /**
         * JSON Schema semantics: an object accepts properties its schema does not list unless the schema says
         * `additionalProperties: false` (the validator otherwise injects that into every object schema). Explicit
         * `additionalProperties: false` violations keep their own key and stay errors.
         */
        private val LEVELS: LevelResolver =
            LevelResolver
                .create()
                .withLevel("validation.schema.additionalProperties", ValidationReport.Level.IGNORE)
                .build()
        private val mapper: JsonMapper = JsonMapper.builder().build()

        /** The contract of [context], `contracts/openapi/<context>.yaml` three levels above the module directory. */
        fun of(context: String): OpenApiContract {
            val module = Paths.get(System.getProperty("user.dir"))
            val file = module.resolve("../../../contracts/openapi/$context.yaml").normalize()
            check(Files.isRegularFile(file)) { "no OpenAPI contract at $file" }
            return OpenApiContract(file)
        }

        private fun documentedOperations(file: Path): List<Operation> {
            val api =
                checkNotNull(OpenAPIV3Parser().read(file.toUri().toString())) { "cannot parse $file" }
            return api.paths
                .flatMap { (template, item) ->
                    item.readOperationsMap().map { (method, operation) ->
                        Operation(
                            checkNotNull(operation.operationId) { "$method $template has no operationId" },
                            method.toRequestMethod(),
                            template,
                            operation.responses.keys.toList(),
                        )
                    }
                }.sortedWith(Operation.SPECIFIC_FIRST)
        }

        private fun PathItem.HttpMethod.toRequestMethod(): Request.Method = Request.Method.valueOf(name)

        private fun HttpHeaders.copy(into: (String, List<String>) -> Unit) {
            forEach { name, values -> into(name, values) }
        }

        private fun decodeQuery(value: String): String = URLDecoder.decode(value, Charsets.UTF_8)

        private fun describe(report: ValidationReport): String =
            report.messages
                .filter { it.level == ValidationReport.Level.ERROR }
                .joinToString("") { "\n    [${it.key}] ${it.message}" }

        private fun decode(body: ByteArray?): Any? =
            body?.let { bytes -> runCatching { mapper.readValue(bytes, Any::class.java) }.getOrNull() }
    }
}
