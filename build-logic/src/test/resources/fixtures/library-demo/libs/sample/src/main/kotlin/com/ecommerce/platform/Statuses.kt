package com.ecommerce.platform

import org.springframework.http.HttpStatus

private const val FIRST_CLIENT_ERROR = 400
private const val LAST_CLIENT_ERROR = 499

/** The Spring status for [code]. */
fun statusOf(code: Int): HttpStatus = HttpStatus.valueOf(code)

/** True for the 4xx range. */
fun isClientError(code: Int): Boolean = code in FIRST_CLIENT_ERROR..LAST_CLIENT_ERROR
