package dev.reichling.adapter.kafka

import java.util.Properties
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer

/** Where a consumer group starts when it has no committed offset. */
enum class AutoOffsetReset(internal val kafkaValue: String) {
    /** Start with the oldest available record. */
    EARLIEST("earliest"),

    /** Start with records produced after the consumer joined. */
    LATEST("latest"),
}

/**
 * Configuration of a [KafkaConsumerAdapter].
 *
 * Auto-commit and the byte-array deserializers are fixed by the adapter and cannot be overridden:
 * [additionalProperties] must not contain them (nor the properties that have a typed field here).
 * Validation happens on construction.
 *
 * @property bootstrapServers comma-separated `host:port` list of brokers; not blank
 * @property groupId consumer group id; not blank
 * @property clientId optional client id, shown in broker logs and metrics
 * @property autoOffsetReset where to start without a committed offset; defaults to [AutoOffsetReset.EARLIEST]
 *   because for a data pipeline, missing existing history is worse than processing it
 * @property maxPollRecords optional upper bound of records returned by one poll; positive
 * @property additionalProperties further Kafka consumer properties, passed to the client unchanged
 */
data class KafkaConsumerSettings(
    val bootstrapServers: String,
    val groupId: String,
    val clientId: String? = null,
    val autoOffsetReset: AutoOffsetReset = AutoOffsetReset.EARLIEST,
    val maxPollRecords: Int? = null,
    val additionalProperties: Map<String, String> = emptyMap(),
) {
    init {
        require(bootstrapServers.isNotBlank()) { "bootstrapServers must not be blank" }
        require(groupId.isNotBlank()) { "groupId must not be blank" }
        require(maxPollRecords == null || maxPollRecords > 0) {
            "maxPollRecords must be positive, but was $maxPollRecords"
        }
        additionalProperties.keys.firstOrNull { it in ADAPTER_OWNED_KEYS }?.let { key ->
            throw IllegalArgumentException(
                "additionalProperties must not contain '$key': the adapter controls this property",
            )
        }
    }

    /** The Kafka client configuration these settings stand for. */
    internal fun toProperties(): Properties {
        val properties = Properties()
        properties.putAll(additionalProperties)
        properties[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = bootstrapServers
        properties[ConsumerConfig.GROUP_ID_CONFIG] = groupId
        properties[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = autoOffsetReset.kafkaValue
        clientId?.let { properties[ConsumerConfig.CLIENT_ID_CONFIG] = it }
        maxPollRecords?.let { properties[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = it }
        // Owned by the adapter: commits are explicit, and payloads stay raw bytes so that
        // deserialization can never fail inside poll().
        properties[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = false
        properties[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = ByteArrayDeserializer::class.java.name
        properties[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = ByteArrayDeserializer::class.java.name
        return properties
    }

    internal companion object {
        val ADAPTER_OWNED_KEYS: Set<String> = setOf(
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            ConsumerConfig.GROUP_ID_CONFIG,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
        )
    }
}
