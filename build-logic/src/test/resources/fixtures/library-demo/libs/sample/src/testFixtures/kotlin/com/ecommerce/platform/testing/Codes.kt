package com.ecommerce.platform.testing

/** Status codes on both sides of the 4xx boundaries, published to other modules' tests. */
object Codes {
    const val LAST_REDIRECT = 399
    const val FIRST_CLIENT_ERROR = 400
    const val NOT_FOUND = 404
    const val LAST_CLIENT_ERROR = 499
    const val FIRST_SERVER_ERROR = 500
}
