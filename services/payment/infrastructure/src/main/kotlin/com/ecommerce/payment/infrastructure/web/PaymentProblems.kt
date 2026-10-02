package com.ecommerce.payment.infrastructure.web

import arrow.core.nonEmptyListOf
import com.ecommerce.payment.domain.PaymentError
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.result.ValidationError

/** The RFC 9457 problems of payment.yaml and payment-internal.yaml for each [PaymentError]. */
object PaymentProblems {
    /** The problem answering [error]. */
    fun of(error: PaymentError): Problem =
        when (error) {
            is PaymentError.Invalid -> {
                Problem.validation(nonEmptyListOf(ValidationError(error.field, error.reason)))
            }

            PaymentError.IdempotencyKeyReuse -> {
                Problem.validation(
                    nonEmptyListOf(ValidationError("Idempotency-Key", "already used with a different request")),
                    "Idempotency-Key was already used with a different request.",
                )
            }

            PaymentError.AlreadyCharged -> {
                Problem.conflict("The order already has an approved charge.")
            }

            PaymentError.NotRefundable -> {
                Problem.conflict("Only an approved charge can be refunded.")
            }

            PaymentError.AlreadyRefunded -> {
                Problem.conflict("The charge has already been refunded.")
            }

            PaymentError.AttemptNotFound -> {
                Problem.notFound("No such charge attempt for this order.")
            }

            PaymentError.NotFound -> {
                Problem.notFound("Resource not found.")
            }

            PaymentError.Forbidden -> {
                Problem.forbidden("This operation requires the operator role.")
            }
        }
}
