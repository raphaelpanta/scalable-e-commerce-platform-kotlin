package com.ecommerce.platform.core.problem

import arrow.core.nonEmptyListOf
import com.ecommerce.platform.core.result.ValidationError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.net.URI

class ProblemTypeSpec :
    FunSpec({
        test("every type has the platform URI of its slug") {
            ProblemType.entries.forEach { type ->
                type.uri shouldBe URI.create("https://ecommerce.example/problems/${type.slug}")
                ProblemType.fromSlug(type.slug) shouldBe type
                ProblemType.fromUri(type.uri) shouldBe type
                ProblemType.uriOf(type.slug) shouldBe type.uri
            }
            ProblemType.BASE_URI shouldBe "https://ecommerce.example/problems/"
            ProblemType.fromSlug("nope") shouldBe null
            ProblemType.fromUri(URI.create("about:blank")) shouldBe null
        }

        test("statuses and titles follow the contracts") {
            ProblemType.entries.associate { it.slug to (it.status to it.title) } shouldBe
                mapOf(
                    "validation" to (422 to "Validation failed"),
                    "not-found" to (404 to "Not found"),
                    "conflict" to (409 to "Conflict"),
                    "throttled" to (429 to "Too many requests"),
                    "insufficient-stock" to (409 to "Insufficient stock"),
                    "price-changed" to (409 to "Price changed"),
                    "stale-revision" to (409 to "Stale revision"),
                    "unauthorized" to (401 to "Unauthorized"),
                    "forbidden" to (403 to "Forbidden"),
                    "unavailable" to (503 to "Service unavailable"),
                )
        }

        test("a 400 validation problem is titled Bad request") {
            ProblemType.VALIDATION.titleFor(400) shouldBe "Bad request"
            ProblemType.VALIDATION.titleFor(422) shouldBe "Validation failed"
            ProblemType.CONFLICT.titleFor(400) shouldBe "Conflict"
        }

        test("generic types by status") {
            mapOf(
                400 to ProblemType.VALIDATION,
                422 to ProblemType.VALIDATION,
                401 to ProblemType.UNAUTHORIZED,
                403 to ProblemType.FORBIDDEN,
                404 to ProblemType.NOT_FOUND,
                409 to ProblemType.CONFLICT,
                429 to ProblemType.THROTTLED,
                503 to ProblemType.UNAVAILABLE,
            ).forEach { (status, type) -> ProblemType.forStatus(status) shouldBe type }
            listOf(405, 413, 500, 502).forEach { ProblemType.forStatus(it) shouldBe null }
        }
    })

class ProblemSpec :
    FunSpec({
        test("status must be 4xx or 5xx") {
            shouldThrow<IllegalArgumentException> { Problem(ProblemType.ABOUT_BLANK, "x", 399) }
            shouldThrow<IllegalArgumentException> { Problem(ProblemType.ABOUT_BLANK, "x", 600) }
            Problem(ProblemType.ABOUT_BLANK, "x", 400).status shouldBe 400
            Problem(ProblemType.ABOUT_BLANK, "x", 599).status shouldBe 599
        }

        test("extensions cannot redefine the standard members") {
            Problem.RESERVED_MEMBERS.forEach { name ->
                shouldThrow<IllegalArgumentException> { Problem.notFound().withExtension(name, "x") }
            }
            Problem.notFound().withExtension("unavailableLines", emptyList<Any>()).extensions shouldBe
                mapOf("unavailableLines" to emptyList<Any>())
        }

        test("factories use the catalogue") {
            Problem.of(ProblemType.INSUFFICIENT_STOCK, "short", extensions = mapOf("lines" to 1)) shouldBe
                Problem(
                    ProblemType.INSUFFICIENT_STOCK.uri,
                    "Insufficient stock",
                    409,
                    "short",
                    extensions =
                        mapOf("lines" to 1),
                )
            Problem.notFound("gone") shouldBe Problem(ProblemType.NOT_FOUND.uri, "Not found", 404, "gone")
            Problem.conflict().status shouldBe 409
            Problem.conflict().type shouldBe ProblemType.CONFLICT.uri
            Problem.unauthorized().type shouldBe ProblemType.UNAUTHORIZED.uri
            Problem.forbidden().status shouldBe 403
            Problem.unavailable("down") shouldBe
                Problem(ProblemType.UNAVAILABLE.uri, "Service unavailable", 503, "down")
            Problem.throttled().title shouldBe "Too many requests"
            Problem.badRequest("bad json") shouldBe Problem(ProblemType.VALIDATION.uri, "Bad request", 400, "bad json")
            Problem.internal() shouldBe Problem(URI.create("about:blank"), "Internal Server Error", 500)
            Problem.custom("order-not-cancellable", "Order cannot be cancelled", 409, "shipped") shouldBe
                Problem(
                    URI.create("https://ecommerce.example/problems/order-not-cancellable"),
                    "Order cannot be cancelled",
                    409,
                    "shipped",
                )
            Problem.notFound().problemType shouldBe ProblemType.NOT_FOUND
            Problem.internal().problemType shouldBe null
        }

        test("validation problems list every field error") {
            val errors = nonEmptyListOf(ValidationError("password", "is short"), ValidationError("email", "is taken"))
            val problem = Problem.validation(errors)
            problem.status shouldBe 422
            problem.title shouldBe "Validation failed"
            problem.detail shouldBe "password is short; email is taken"
            problem.extensions shouldBe
                mapOf(
                    "errors" to
                        listOf(Problem.FieldError("password", "is short"), Problem.FieldError("email", "is taken")),
                )
            Problem.validation(errors, "Invalid input.", 400).let {
                it.title shouldBe "Bad request"
                it.detail shouldBe "Invalid input."
            }
        }

        test("bare statuses map to catalogued types or about:blank") {
            Problem.forStatus(404, "Not Found", "no route") shouldBe Problem.notFound("no route")
            Problem.forStatus(400, "Bad Request") shouldBe Problem.badRequest(null)
            Problem.forStatus(405, "Method Not Allowed", "GET only") shouldBe
                Problem(ProblemType.ABOUT_BLANK, "Method Not Allowed", 405, "GET only")
        }

        test("correlation id and instance are filled in") {
            val problem = Problem.notFound().withCorrelationId("c-1").withDefaultInstance("/a")
            problem.correlationId shouldBe "c-1"
            problem.instance shouldBe "/a"
            problem.withDefaultInstance("/b").instance shouldBe "/a"
            problem.withCorrelationId(null).correlationId shouldBe null
        }

        test("JSON members are in contract order and omit absent optionals") {
            Problem.notFound().toJsonMembers() shouldBe
                mapOf("type" to "https://ecommerce.example/problems/not-found", "title" to "Not found", "status" to 404)
            Problem
                .of(ProblemType.PRICE_CHANGED, "changed", extensions = mapOf("currentCartRevision" to "r2"))
                .withDefaultInstance("/api/v1/orders")
                .withCorrelationId("c-1")
                .toJsonMembers()
                .keys
                .toList() shouldContainExactly
                listOf("type", "title", "status", "detail", "instance", "correlationId", "currentCartRevision")
        }

        test("JSON members round-trip") {
            checkAll(
                Arb.enum<ProblemType>(),
                Arb.string(0, 20).orNull(),
                Arb.string(1, 20).orNull(),
                Arb.int(400..599),
            ) { type, detail, correlationId, status ->
                val problem =
                    Problem(type.uri, type.title, status, detail, "/x", correlationId, mapOf("extra" to listOf(1, 2)))
                Problem.fromJsonMembers(problem.toJsonMembers()) shouldBe problem
            }
        }

        test("malformed JSON members are not problems") {
            val valid = Problem.notFound().toJsonMembers()
            Problem.fromJsonMembers(valid - "type") shouldBe null
            Problem.fromJsonMembers(valid - "title") shouldBe null
            Problem.fromJsonMembers(valid - "status") shouldBe null
            Problem.fromJsonMembers(valid + ("status" to "404")) shouldBe null
            Problem.fromJsonMembers(valid + ("status" to 200)) shouldBe null
            Problem.fromJsonMembers(valid + ("status" to 600)) shouldBe null
            Problem.fromJsonMembers(valid + ("type" to "not a uri")) shouldBe null
            Problem.fromJsonMembers(valid + ("type" to 5)) shouldBe null
            Problem.fromJsonMembers(valid + ("status" to 404L)) shouldBe Problem.notFound()
            Problem.fromJsonMembers(valid + ("detail" to 5)) shouldBe Problem.notFound()
        }
    })
