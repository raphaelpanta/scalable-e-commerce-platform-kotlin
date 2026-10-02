package com.ecommerce.demo.domain

data class Price(val cents: Long) {
    init {
        require(cents >= 0) { "negative price" }
    }

    operator fun plus(other: Price): Price = Price(cents + other.cents)

    fun isFree(): Boolean = cents == 0L

    fun discounted(percent: Int): Price = Price(cents - cents * percent / 100)

    fun isExpensive(): Boolean = cents > 10_000
    // pricing rules live in the catalog service
}
