package com.ecommerce.platform.core.problem

import arrow.core.NonEmptyList
import com.ecommerce.platform.core.result.ValidationError
import java.net.URI

/**
 * An RFC 9457 problem as every platform API returns it (`application/problem+json`, service conventions section 3):
 * [type], [title] and [status] always; [detail] and [instance] when known; [correlationId] filled in by the web
 * layer from the request; [extensions] are additional top-level members such as `errors`, `unavailableLines` or
 * `currentCartRevision`. Pure Kotlin: the Spring layer renders [toJsonMembers].
 */
data class Problem(
    val type: URI,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
    val correlationId: String? = null,
    val extensions: Map<String, Any?> = emptyMap(),
) {
    init {
        require(status in MIN_STATUS..MAX_STATUS) { "a problem status must be 4xx or 5xx, was $status" }
        require(extensions.keys.none { it in RESERVED_MEMBERS }) { "extensions must not redefine ${extensions.keys}" }
    }

    /** The catalogued type of this problem, if it has one. */
    val problemType: ProblemType? get() = ProblemType.fromUri(type)

    /** This problem carrying [correlationId]. */
    fun withCorrelationId(correlationId: String?): Problem = copy(correlationId = correlationId)

    /** This problem with [instance] when it has none yet (the request path, typically). */
    fun withDefaultInstance(instance: String?): Problem = if (this.instance == null) copy(instance = instance) else this

    /** This problem with one more extension member. */
    fun withExtension(
        name: String,
        value: Any?,
    ): Problem = copy(extensions = extensions + (name to value))

    /**
     * The JSON members in contract order: `type`, `title`, `status`, then `detail`, `instance` and `correlationId`
     * when present, then the extensions.
     */
    fun toJsonMembers(): Map<String, Any?> {
        val members = linkedMapOf<String, Any?>(TYPE to type.toString(), TITLE to title, STATUS to status)
        detail?.let { members[DETAIL] = it }
        instance?.let { members[INSTANCE] = it }
        correlationId?.let { members[CORRELATION_ID] = it }
        members.putAll(extensions)
        return members
    }

    /** One entry of the `errors` extension of a validation problem. */
    data class FieldError(
        val field: String,
        val message: String,
    )

    @Suppress("TooManyFunctions") // one factory per catalogued problem type keeps call sites short
    companion object {
        const val TYPE: String = "type"
        const val TITLE: String = "title"
        const val STATUS: String = "status"
        const val DETAIL: String = "detail"
        const val INSTANCE: String = "instance"
        const val CORRELATION_ID: String = "correlationId"
        const val ERRORS: String = "errors"

        /** Members a problem defines itself; extensions cannot use these names. */
        val RESERVED_MEMBERS: Set<String> = setOf(TYPE, TITLE, STATUS, DETAIL, INSTANCE, CORRELATION_ID)

        private const val MIN_STATUS = 400
        private const val MAX_STATUS = 599
        private const val BAD_REQUEST = 400
        private const val INTERNAL_ERROR = 500
        private const val INTERNAL_ERROR_TITLE = "Internal Server Error"

        /** A problem of a catalogued [type]; [status] defaults to the type's status. */
        fun of(
            type: ProblemType,
            detail: String? = null,
            status: Int = type.status,
            extensions: Map<String, Any?> = emptyMap(),
        ): Problem = Problem(type.uri, type.titleFor(status), status, detail, extensions = extensions)

        /** A service-specific problem type `https://ecommerce.example/problems/<slug>`. */
        fun custom(
            slug: String,
            title: String,
            status: Int,
            detail: String? = null,
            extensions: Map<String, Any?> = emptyMap(),
        ): Problem = Problem(ProblemType.uriOf(slug), title, status, detail, extensions = extensions)

        /** 422 `Validation failed` (or 400 when [status] says so) with one `errors` entry per broken rule. */
        fun validation(
            errors: NonEmptyList<ValidationError>,
            detail: String? = null,
            status: Int = ProblemType.VALIDATION.status,
        ): Problem =
            of(
                ProblemType.VALIDATION,
                detail ?: errors.joinToString("; ") { "${it.field} ${it.reason}" },
                status,
                mapOf(ERRORS to errors.map { FieldError(it.field, it.reason) }),
            )

        /** 400 `Bad request` (validation type) for a malformed request: unreadable body, missing header, bad query. */
        fun badRequest(detail: String?): Problem = of(ProblemType.VALIDATION, detail, BAD_REQUEST)

        /** 404 `Not found`. */
        fun notFound(detail: String? = null): Problem = of(ProblemType.NOT_FOUND, detail)

        /** 409 `Conflict`. */
        fun conflict(detail: String? = null): Problem = of(ProblemType.CONFLICT, detail)

        /** 401 `Unauthorized`. */
        fun unauthorized(detail: String? = null): Problem = of(ProblemType.UNAUTHORIZED, detail)

        /** 403 `Forbidden`. */
        fun forbidden(detail: String? = null): Problem = of(ProblemType.FORBIDDEN, detail)

        /** 503 `Service unavailable`. */
        fun unavailable(detail: String? = null): Problem = of(ProblemType.UNAVAILABLE, detail)

        /** 429 `Too many requests`; the web layer adds `Retry-After`. */
        fun throttled(detail: String? = null): Problem = of(ProblemType.THROTTLED, detail)

        /** 500 `about:blank` without any detail: internals never leak to clients. */
        fun internal(): Problem = Problem(ProblemType.ABOUT_BLANK, INTERNAL_ERROR_TITLE, INTERNAL_ERROR)

        /**
         * The problem for a bare HTTP [status]: the catalogued type when one matches (400 and 422 `validation`, 401,
         * 403, 404, 409 `conflict`, 429, 503), otherwise `about:blank` titled [reasonPhrase].
         */
        fun forStatus(
            status: Int,
            reasonPhrase: String,
            detail: String? = null,
        ): Problem =
            ProblemType.forStatus(status)?.let { of(it, detail, status) }
                ?: Problem(ProblemType.ABOUT_BLANK, reasonPhrase, status, detail)

        /**
         * Reads a problem back from its JSON members (HTTP clients); `null` when the mandatory `type`, `title` or
         * `status` member is missing or malformed.
         */
        fun fromJsonMembers(members: Map<String, Any?>): Problem? {
            val type = (members[TYPE] as? String)?.let { runCatching { URI.create(it) }.getOrNull() }
            val title = members[TITLE] as? String
            val status = (members[STATUS] as? Number)?.toInt()?.takeIf { it in MIN_STATUS..MAX_STATUS }
            return if (type == null || title == null || status == null) {
                null
            } else {
                Problem(
                    type = type,
                    title = title,
                    status = status,
                    detail = members[DETAIL] as? String,
                    instance = members[INSTANCE] as? String,
                    correlationId = members[CORRELATION_ID] as? String,
                    extensions = members.filterKeys { it !in RESERVED_MEMBERS },
                )
            }
        }
    }
}
