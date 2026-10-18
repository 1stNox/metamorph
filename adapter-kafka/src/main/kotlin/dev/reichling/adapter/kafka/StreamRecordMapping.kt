package dev.reichling.adapter.kafka

import dev.reichling.core.model.RecordOrigin
import dev.reichling.core.model.StreamRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.time.Instant

/**
 * Maps a Kafka record to the platform-neutral model. Null key and value stay null, and headers
 * keep their order and duplicates.
 */
internal fun ConsumerRecord<ByteArray, ByteArray>.toStreamRecord(): StreamRecord =
    StreamRecord(
        key = key(),
        value = value(),
        headers = headers().map { StreamRecord.Header(it.key(), it.value()) },
        timestamp = Instant.ofEpochMilli(timestamp()),
        origin = RecordOrigin(stream = topic(), partition = partition(), offset = offset()),
    )
