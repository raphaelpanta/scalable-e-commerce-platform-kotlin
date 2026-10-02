package demo

data class Price(val cents: Long) {
    init {
        require(cents >= 0) { "price must not be negative" }
    }
}
