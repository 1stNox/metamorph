package dev.reichling.adapter.kafka

import java.util.Optional
import kotlin.time.Duration.Companion.seconds
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType

/** A [MockConsumer] that counts and can fail commits, and exposes what the adapter registered. */
class RecordingMockConsumer : MockConsumer<ByteArray, ByteArray>("earliest") {
    var commitSyncCalls = 0
        private set
    var commitFailure: RuntimeException? = null
    var rebalanceListener: ConsumerRebalanceListener? = null
        private set
    var closeOptions: CloseOptions? = null
        private set

    override fun commitSync(offsets: Map<TopicPartition, OffsetAndMetadata>) {
        commitSyncCalls++
        commitFailure?.let { throw it }
        super.commitSync(offsets)
    }

    override fun subscribe(topics: Collection<String>, listener: ConsumerRebalanceListener) {
        rebalanceListener = listener
        super.subscribe(topics, listener)
    }

    override fun close(options: CloseOptions) {
        closeOptions = options
        super.close(options)
    }
}

/** An adapter wired to a [RecordingMockConsumer], with helpers to assign partitions and add records. */
class MockConsumerFixture {
    val mock = RecordingMockConsumer()
    val adapter = KafkaConsumerAdapter(mock, closeTimeout = 3.seconds)

    /** Subscribes the adapter and hands [partitions] to it, as a rebalance would. */
    fun subscribeAndAssign(streams: Set<String>, vararg partitions: TopicPartition) {
        adapter.subscribe(streams)
        assign(*partitions)
    }

    fun assign(vararg partitions: TopicPartition) {
        mock.rebalance(partitions.toList())
        mock.updateBeginningOffsets(partitions.associateWith { 0L })
    }

    fun addRecords(partition: TopicPartition, offsets: LongRange) {
        offsets.forEach { offset ->
            addRecord(partition, offset, key = "k$offset".toByteArray(), value = "v$offset".toByteArray())
        }
    }

    fun addRecord(
        partition: TopicPartition,
        offset: Long,
        key: ByteArray? = null,
        value: ByteArray? = null,
        headers: List<Pair<String, ByteArray?>> = emptyList(),
        timestamp: Long = 1_700_000_000_123L,
    ) {
        mock.addRecord(
            ConsumerRecord(
                partition.topic(),
                partition.partition(),
                offset,
                timestamp,
                TimestampType.CREATE_TIME,
                key?.size ?: ConsumerRecord.NULL_SIZE,
                value?.size ?: ConsumerRecord.NULL_SIZE,
                key,
                value,
                RecordHeaders(headers.map { (name, headerValue) -> RecordHeader(name, headerValue) }),
                Optional.empty(),
            ),
        )
    }

    fun committedOffset(partition: TopicPartition): Long? =
        mock.committed(setOf(partition))[partition]?.offset()
}
