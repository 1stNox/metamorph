package dev.reichling.adapter.kafka

import dev.reichling.core.model.StreamRecord
import dev.reichling.core.port.CommitRejectedException
import dev.reichling.core.port.EventStreamConsumer
import dev.reichling.core.port.EventStreamException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.RebalanceInProgressException

/**
 * [EventStreamConsumer] on top of a Kafka consumer (the adapted service).
 *
 * Offsets are committed only by [commit], synchronously and per partition: the highest delivered
 * offset plus one. When Kafka takes partitions away, their tracked offsets are dropped without
 * committing, so a rebalance never acknowledges records that were not processed.
 *
 * Like the Kafka consumer, this class is not thread-safe. Rebalance callbacks run inside [poll]
 * on the polling thread, so the offset tracking needs no synchronization.
 */
class KafkaConsumerAdapter internal constructor(
    private val consumer: Consumer<ByteArray, ByteArray>,
    private val closeTimeout: Duration = 10.seconds,
) : EventStreamConsumer {

    /** Creates an adapter with a new Kafka consumer configured from [settings]. */
    constructor(settings: KafkaConsumerSettings) : this(createConsumer(settings))

    /** Highest delivered offset per partition since the last successful commit. */
    private val pending = HashMap<TopicPartition, Long>()

    private var subscribed = false
    private var closed = false

    private val rebalanceListener = object : ConsumerRebalanceListener {
        override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) = Unit

        override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) {
            pending.keys.removeAll(partitions.toSet())
        }

        override fun onPartitionsLost(partitions: Collection<TopicPartition>) {
            pending.keys.removeAll(partitions.toSet())
        }
    }

    override fun subscribe(streams: Set<String>) {
        checkNotClosed()
        require(streams.isNotEmpty()) { "streams must not be empty" }
        translating { consumer.subscribe(streams, rebalanceListener) }
        subscribed = true
    }

    override fun poll(timeout: Duration): List<StreamRecord> {
        checkSubscribed()
        val records = translating { consumer.poll(timeout.toJavaDuration()) }
        return records.map { record ->
            pending.merge(TopicPartition(record.topic(), record.partition()), record.offset(), ::maxOf)
            record.toStreamRecord()
        }
    }

    override fun commit() {
        checkSubscribed()
        if (pending.isEmpty()) return
        val offsets = pending.mapValues { (_, highestDelivered) -> OffsetAndMetadata(highestDelivered + 1) }
        translating { consumer.commitSync(offsets) }
        pending.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        translating { consumer.close(CloseOptions.timeout(closeTimeout.toJavaDuration())) }
    }

    private fun checkNotClosed() = check(!closed) { "The consumer is closed" }

    private fun checkSubscribed() {
        checkNotClosed()
        check(subscribed) { "The consumer is not subscribed to any stream; call subscribe() first" }
    }

    private companion object {
        fun createConsumer(settings: KafkaConsumerSettings): Consumer<ByteArray, ByteArray> =
            translating { KafkaConsumer(settings.toProperties()) }
    }
}

/**
 * Runs a call on the Kafka client and translates its failures into port exceptions, so no Kafka
 * exception escapes the adapter. Programming errors (`IllegalStateException`,
 * `IllegalArgumentException`) are not `KafkaException`s and pass through unchanged.
 */
private inline fun <T> translating(block: () -> T): T =
    try {
        block()
    } catch (e: CommitFailedException) {
        throw CommitRejectedException("Commit rejected, the records will be redelivered: ${e.message}", e)
    } catch (e: RebalanceInProgressException) {
        throw CommitRejectedException("Commit rejected, the records will be redelivered: ${e.message}", e)
    } catch (e: KafkaException) {
        throw EventStreamException("Kafka client failure: ${e.message}", e)
    }
