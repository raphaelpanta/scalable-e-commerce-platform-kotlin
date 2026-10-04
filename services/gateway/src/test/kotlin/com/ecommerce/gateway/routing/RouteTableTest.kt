package com.ecommerce.gateway.routing

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContainDuplicates
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.util.unit.DataSize
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.URI

private const val ID = "a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

/** One public operation and the route policy the gateway must apply to it. */
private data class Expected(
    val method: String,
    val path: String,
    val route: String,
    val auth: AuthRequirement,
    val tier: Tier,
    val host: String,
)

private fun identity(
    method: String,
    path: String,
    route: String,
    auth: AuthRequirement,
    tier: Tier = Tier.STANDARD,
) = Expected(method, "/api/v1/identity$path", route, auth, tier, "identity")

private fun catalog(
    method: String,
    path: String,
    route: String,
    auth: AuthRequirement,
    tier: Tier,
) = Expected(method, "/api/v1/catalog$path", route, auth, tier, "catalog")

private val ANON = AuthRequirement.ANONYMOUS
private val AUTHENTICATED = AuthRequirement.AUTHENTICATED
private val SHOPPER = AuthRequirement.SHOPPER
private val OPERATOR = AuthRequirement.OPERATOR

/** contracts/gateway-routes.md applied to every operation of the contracts/openapi files. */
private val expectations =
    listOf(
        identity("POST", "/accounts", "identity-registration", ANON, Tier.AUTH),
        identity("POST", "/accounts/verify-email", "identity-registration", ANON, Tier.AUTH),
        identity("POST", "/sessions", "identity-credentials", ANON, Tier.AUTH),
        identity("POST", "/sessions/refresh", "identity-credentials", ANON, Tier.AUTH),
        identity("DELETE", "/sessions/current", "identity-sign-out", AUTHENTICATED),
        identity("POST", "/password-resets", "identity-credentials", ANON, Tier.AUTH),
        identity("POST", "/password-resets/complete", "identity-credentials", ANON, Tier.AUTH),
        identity("GET", "/accounts/me", "identity-profile", AUTHENTICATED),
        identity("PUT", "/accounts/me", "identity-profile", AUTHENTICATED),
        identity("DELETE", "/accounts/me", "identity-account-deletion", SHOPPER),
        identity("GET", "/accounts/me/addresses", "identity-addresses", AUTHENTICATED),
        identity("POST", "/accounts/me/addresses", "identity-addresses", AUTHENTICATED),
        identity("PUT", "/accounts/me/addresses/$ID", "identity-address", AUTHENTICATED),
        identity("DELETE", "/accounts/me/addresses/$ID", "identity-address", AUTHENTICATED),
        identity("GET", "/accounts/me/notification-preferences", "identity-profile", AUTHENTICATED),
        identity("PUT", "/accounts/me/notification-preferences", "identity-profile", AUTHENTICATED),
        identity("POST", "/accounts/me/phone-verifications", "identity-phone-verification", AUTHENTICATED),
        identity("POST", "/accounts/me/phone-verifications/confirm", "identity-phone-verification", AUTHENTICATED),
        catalog("GET", "/products", "catalog-reads", ANON, Tier.BROWSE),
        catalog("GET", "/products/$ID", "catalog-reads", ANON, Tier.BROWSE),
        catalog("GET", "/categories", "catalog-reads", ANON, Tier.BROWSE),
        catalog("GET", "/categories/$ID", "catalog-reads", ANON, Tier.BROWSE),
        catalog("POST", "/products", "catalog-creations", OPERATOR, Tier.OPERATOR),
        catalog("PUT", "/products/$ID", "catalog-updates", OPERATOR, Tier.OPERATOR),
        catalog("POST", "/products/$ID/withdrawal", "catalog-creations", OPERATOR, Tier.OPERATOR),
        catalog("POST", "/products/$ID/reinstatement", "catalog-creations", OPERATOR, Tier.OPERATOR),
        catalog("POST", "/products/$ID/stock-adjustments", "catalog-creations", OPERATOR, Tier.OPERATOR),
        catalog("POST", "/products/$ID/images", "catalog-image-registration", OPERATOR, Tier.OPERATOR),
        catalog("POST", "/categories", "catalog-creations", OPERATOR, Tier.OPERATOR),
        catalog("PUT", "/categories/$ID", "catalog-updates", OPERATOR, Tier.OPERATOR),
        catalog("POST", "/categories/$ID/withdrawal", "catalog-creations", OPERATOR, Tier.OPERATOR),
        catalog("POST", "/categories/$ID/reinstatement", "catalog-creations", OPERATOR, Tier.OPERATOR),
        Expected("GET", "/api/v1/cart", "cart", ANON, Tier.STANDARD, "cart"),
        Expected("DELETE", "/api/v1/cart", "cart", ANON, Tier.STANDARD, "cart"),
        Expected("POST", "/api/v1/cart/lines", "cart-line-addition", ANON, Tier.STANDARD, "cart"),
        Expected("PUT", "/api/v1/cart/lines/$ID", "cart-line", ANON, Tier.STANDARD, "cart"),
        Expected("DELETE", "/api/v1/cart/lines/$ID", "cart-line", ANON, Tier.STANDARD, "cart"),
        Expected("POST", "/api/v1/cart/merge", "cart-merge", AUTHENTICATED, Tier.STANDARD, "cart"),
        Expected("POST", "/api/v1/orders", "order-placement", SHOPPER, Tier.CHECKOUT, "order"),
        Expected("GET", "/api/v1/orders", "order-history", SHOPPER, Tier.STANDARD, "order"),
        Expected("GET", "/api/v1/orders/$ID", "order-read", AUTHENTICATED, Tier.STANDARD, "order"),
        Expected("POST", "/api/v1/orders/$ID/cancellation", "order-cancellation", SHOPPER, Tier.STANDARD, "order"),
        Expected("POST", "/api/v1/orders/$ID/status", "order-status", OPERATOR, Tier.OPERATOR, "order"),
        Expected("GET", "/api/v1/payments/attempts", "payment-reads", AUTHENTICATED, Tier.STANDARD, "payment"),
        Expected("GET", "/api/v1/payments/attempts/$ID", "payment-reads", AUTHENTICATED, Tier.STANDARD, "payment"),
        Expected("GET", "/api/v1/payments/refunds", "payment-reads", AUTHENTICATED, Tier.STANDARD, "payment"),
        Expected("GET", "/api/v1/payments/refunds/$ID", "payment-reads", AUTHENTICATED, Tier.STANDARD, "payment"),
        Expected(
            "GET",
            "/api/v1/payments/simulator/rules",
            "payment-simulator-rules",
            OPERATOR,
            Tier.OPERATOR,
            "payment",
        ),
        Expected("GET", "/api/v1/notifications", "notification-list", SHOPPER, Tier.STANDARD, "notification"),
        Expected(
            "GET",
            "/api/v1/notifications/failed",
            "notification-failed",
            OPERATOR,
            Tier.OPERATOR,
            "notification",
        ),
        Expected(
            "POST",
            "/api/v1/notifications/$ID/retry",
            "notification-retry",
            OPERATOR,
            Tier.OPERATOR,
            "notification",
        ),
    )

/** Requests that must match no route (deny by default: 404, nothing forwarded). */
private val unrouted =
    listOf(
        "GET" to "/internal/accounts/$ID/contact",
        "GET" to "/internal/catalog/products",
        "POST" to "/internal/payments/charges",
        "GET" to "/.well-known/jwks.json",
        "GET" to "/actuator/health",
        "GET" to "/actuator/prometheus",
        "GET" to "/",
        "GET" to "/api/v1/unknown",
        "GET" to "/api/v2/catalog/products",
        "DELETE" to "/api/v1/catalog/products/$ID",
        "PATCH" to "/api/v1/catalog/products/$ID",
        "POST" to "/api/v1/catalog/products/$ID",
        "POST" to "/api/v1/catalog/categories/$ID",
        "PUT" to "/api/v1/catalog/categories/$ID/withdrawal",
        "PUT" to "/api/v1/catalog/products/$ID/reinstatement",
        "GET" to "/api/v1/catalog/categories/$ID/reinstatement",
        "GET" to "/api/v1/catalog/products/$ID/images",
        "PUT" to "/api/v1/orders/$ID",
        "DELETE" to "/api/v1/orders/$ID",
        "GET" to "/api/v1/identity/accounts",
        "GET" to "/api/v1/identity/sessions",
        "DELETE" to "/api/v1/identity/accounts/me/notification-preferences",
        "POST" to "/api/v1/payments/attempts",
        "GET" to "/api/v1/cart/merge",
        "GET" to "/api/v1/cart/lines/$ID/extra",
    )

private val repositoryRoot: File =
    generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "contracts/openapi").isDirectory }

/** Every (method, path template, anonymous allowed) of the public OpenAPI files. */
@Suppress("UNCHECKED_CAST")
private fun openApiOperations(): List<Triple<String, String, Boolean>> =
    File(repositoryRoot, "contracts/openapi").listFiles { file -> file.extension == "yaml" }.orEmpty().flatMap { file ->
        val document = Yaml().load<Map<String, Any?>>(file.readText())
        val defaultSecurity = document["security"] as List<Map<String, Any?>>?
        (document["paths"] as Map<String, Map<String, Any?>>).flatMap { (path, operations) ->
            operations
                .filterKeys { it in setOf("get", "post", "put", "patch", "delete", "head") }
                .map { (method, operation) ->
                    val security =
                        (operation as Map<String, Any?>)["security"] as List<Map<String, Any?>>? ?: defaultSecurity
                    Triple(method.uppercase(), path, security.isNullOrEmpty() || security.any { it.isEmpty() })
                }
        }
    }

class RouteTableTest :
    FunSpec({
        val table = RouteTable.load()

        test("every route declares a valid policy whose timeout is its tier's") {
            table.routes.map { it.id }.shouldNotContainDuplicates()
            table.routes.forEach { route -> RoutePolicy.from(route.id.orEmpty(), route.metadata) }
            RoutePolicies(table.properties) shouldNotBe null
        }

        test("every public operation is routed with the auth requirement and tier of gateway-routes.md") {
            assertSoftly {
                expectations.forEach { expected ->
                    withClue("${expected.method} ${expected.path}") {
                        val route = table.match(expected.method, expected.path).shouldNotBeNull()
                        val policy = RoutePolicy.from(route.id.orEmpty(), route.metadata)
                        route.id shouldBe expected.route
                        policy.auth shouldBe expected.auth
                        policy.tier shouldBe expected.tier
                    }
                }
            }
        }

        test("routes point at the service URL variables, defaulting to http://localhost:8080") {
            val custom =
                RouteTable.load(expectations.associate { "${it.host.uppercase()}_URL" to "http://${it.host}:8080" })
            expectations.forEach { expected ->
                custom.match(expected.method, expected.path).shouldNotBeNull().uri shouldBe
                    URI("http://${expected.host}:8080")
                table.match(expected.method, expected.path).shouldNotBeNull().uri shouldBe URI("http://localhost:8080")
            }
        }

        test("internal, discovery, management and unknown paths match no route") {
            unrouted.forEach { (method, path) ->
                withClue("$method $path") { table.match(method, path).shouldBeNull() }
            }
        }

        test("the expectations cover every operation of the public OpenAPI files and their security") {
            val operations = openApiOperations()
            val missing =
                operations.filter { (method, template, _) ->
                    val path = template.replace(Regex("\\{[^}]+}"), ID)
                    expectations.none { it.method == method && it.path == path }
                }
            missing.shouldBeEmpty()
            operations.forEach { (method, template, anonymousAllowed) ->
                val path = template.replace(Regex("\\{[^}]+}"), ID)
                val route = table.match(method, path).shouldNotBeNull()
                withClue("$method $template") {
                    (RoutePolicy.from(route.id.orEmpty(), route.metadata).auth == ANON) shouldBe anonymousAllowed
                }
            }
        }

        test("only the image registration raises the body limit, to 5 MiB") {
            table.routes
                .mapNotNull { route ->
                    RoutePolicy.from(route.id.orEmpty(), route.metadata).maxBodySize?.let {
                        route.id to
                            it
                    }
                }.shouldBe(listOf("catalog-image-registration" to DataSize.ofMegabytes(5)))
        }

        test("POST /api/v1/orders is outside the retried methods") {
            val retry = table.properties.defaultFilters.single { it.name == "Retry" }
            retry.args["methods"] shouldBe "GET,HEAD"
            retry.args["series"] shouldBe ""
            retry.args["statuses"] shouldBe ""
            retry.args["exceptions"] shouldBe "java.net.ConnectException"
        }
    })
