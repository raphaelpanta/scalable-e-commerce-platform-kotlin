package com.ecommerce.platform.problem

import com.ecommerce.platform.core.problem.Problem

/**
 * Thrown by a handler (or an adapter) to answer with [problem]; [ProblemExceptionHandler] renders it as
 * `application/problem+json`. Functional handlers usually return the problem instead ([ProblemResponses]).
 * [headers] are added to the response (for example `Retry-After` on a 429).
 */
class ProblemException(
    val problem: Problem,
    val headers: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(problem.detail ?: problem.title, cause)
