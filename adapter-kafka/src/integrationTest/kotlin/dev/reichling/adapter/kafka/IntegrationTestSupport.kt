package dev.reichling.adapter.kafka

import dev.reichling.core.model.StreamRecord
import dev.reichling.core.port.EventStreamConsumer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Generous: the first poll of a new consumer includes joining the group. */
val DEFAULT_DEADLINE: Duration = 30.seconds

/**
 * Polls [consumer] until at least [expectedCount] records arrived or [deadline] passed, and returns
 * everything received. Every wait is bounded, so a missing record fails the test instead of hanging it.
 */
fun pollUntil(
    consumer: EventStreamConsumer,
    expectedCount: Int,
    deadline: Duration = DEFAULT_DEADLINE,
): List<StreamRecord> {
    val start = TimeSource.Monotonic.markNow()
    val received = mutableListOf<StreamRecord>()
    while (received.size < expectedCount && start.elapsedNow() < deadline) {
        received += consumer.poll(200.milliseconds)
    }
    return received
}

/** Settings for a test: a random group id unless one is given, so tests never share committed offsets. */
fun testSettings(
    groupId: String = KafkaTestBroker.uniqueName("group"),
    autoOffsetReset: AutoOffsetReset = AutoOffsetReset.EARLIEST,
) = KafkaConsumerSettings(
    bootstrapServers = KafkaTestBroker.bootstrapServers,
    groupId = groupId,
    autoOffsetReset = autoOffsetReset,
)
