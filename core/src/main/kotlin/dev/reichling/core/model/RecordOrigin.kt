package dev.reichling.core.model

/**
 * Where a [StreamRecord] came from on its streaming platform.
 *
 * Together the three fields identify a record uniquely, so later pipeline stages can use them
 * as a deduplication key when at-least-once delivery produces duplicates.
 *
 * @property stream name of the stream (for Kafka: the topic)
 * @property partition partition number within the stream
 * @property offset position of the record within the partition
 */
data class RecordOrigin(
    val stream: String,
    val partition: Int,
    val offset: Long,
)
