package com.ecommerce.platform.messaging.envelope

private val SEGMENT = Regex("[a-z][a-z0-9]*")
private val TOPIC = Regex("[a-z][a-z0-9]*\\.[a-z][a-z0-9]*\\.v[1-9][0-9]*")
private const val LOW_VOLUME_PARTITIONS = 3
private const val HIGH_VOLUME_PARTITIONS = 6

/**
 * Kafka topic names `<context>.<aggregate>.v<version>` (contracts/asyncapi/events.yaml). The constants are
 * compile-time strings so they can be used in `@KafkaListener(topics = [Topic.ORDER])`; poison messages of a
 * topic go to [deadLetter] (`<topic>.dlt`).
 */
object Topic {
    /** Account lifecycle events, key = account id, producer identity. */
    const val ACCOUNT = "identity.account.v1"

    /** Cart events, key = account cart id, producer cart. */
    const val CART = "cart.cart.v1"

    /** Stock reservation outcomes, key = reservation id, producer catalog. */
    const val STOCK = "catalog.stock.v1"

    /** Order lifecycle events, key = order id, producer order. */
    const val ORDER = "order.order.v1"

    /** Payment attempt and refund outcomes, key = payment id, producer payment. */
    const val PAYMENT = "payment.payment.v1"

    /** Notification delivery outcomes, key = notification id, producer notification. */
    const val NOTIFICATION = "notification.notification.v1"

    /** Suffix of the dead-letter topic that receives the records a consumer gave up on. */
    const val DEAD_LETTER_SUFFIX = ".dlt"

    /** The six topics of the platform with their partition counts from the AsyncAPI channel bindings. */
    val PARTITIONS: Map<String, Int> =
        linkedMapOf(
            ACCOUNT to LOW_VOLUME_PARTITIONS,
            CART to LOW_VOLUME_PARTITIONS,
            STOCK to HIGH_VOLUME_PARTITIONS,
            ORDER to HIGH_VOLUME_PARTITIONS,
            PAYMENT to HIGH_VOLUME_PARTITIONS,
            NOTIFICATION to LOW_VOLUME_PARTITIONS,
        )

    /** The six topics of the platform. */
    val ALL: List<String> = PARTITIONS.keys.toList()

    /** Builds `<context>.<aggregate>.v<version>`; both names are lower-case alphanumerics starting with a letter. */
    fun name(
        context: String,
        aggregate: String,
        version: Int = 1,
    ): String {
        require(SEGMENT.matches(context)) { "invalid topic context '$context'" }
        require(SEGMENT.matches(aggregate)) { "invalid topic aggregate '$aggregate'" }
        require(version >= 1) { "topic version must be at least 1, was $version" }
        return "$context.$aggregate.v$version"
    }

    /** True when [topic] follows `<context>.<aggregate>.v<version>`. */
    fun isValid(topic: String): Boolean = TOPIC.matches(topic)

    /** The dead-letter topic of [topic]: `<topic>.dlt`. */
    fun deadLetter(topic: String): String {
        require(isValid(topic)) { "invalid topic '$topic'" }
        return topic + DEAD_LETTER_SUFFIX
    }
}
