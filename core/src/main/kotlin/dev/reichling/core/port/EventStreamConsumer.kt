package dev.reichling.core.port

import dev.reichling.core.model.StreamRecord
import kotlin.time.Duration

/**
 * Port for consuming records from an event streaming platform (Kafka, Pulsar, Kinesis, ...).
 *
 * The caller pulls records with [poll] and decides when they count as processed by calling [commit].
 *
 * **Delivery semantics: at-least-once.** [commit] acknowledges *every* record that earlier [poll]
 * calls delivered on this consumer (cumulative commit). A consumer that starts later in the same
 * consumer group resumes after the last acknowledged record of each partition. Records that were
 * delivered but not committed are delivered again, so downstream processing must tolerate
 * duplicates; [StreamRecord.origin] provides a deduplication key.
 *
 * **No implicit commits.** Implementations never acknowledge records on their own: not
 * periodically, not on [close], and not when partitions are reassigned to another consumer.
 *
 * **Thread confinement.** Implementations are not thread-safe. All calls, including [close],
 * must come from a single thread. To shut down, the owning thread stops its poll loop (which the
 * bounded timeout of [poll] makes possible) and then calls [close].
 */
interface EventStreamConsumer : AutoCloseable {
    /**
     * Starts consuming from [streams]. A later call replaces the previous subscription.
     *
     * @param streams names of the streams to consume; must not be empty
     * @throws IllegalArgumentException if [streams] is empty
     * @throws IllegalStateException if the consumer is closed
     * @throws EventStreamException if the platform fails
     */
    fun subscribe(streams: Set<String>)

    /**
     * Returns the records that become available within [timeout], or an empty list if there are none.
     * Records of the same stream partition are returned in ascending offset order, within one call
     * and across consecutive calls.
     *
     * @param timeout the longest time to wait for records
     * @throws IllegalStateException if [subscribe] was never called, or the consumer is closed
     * @throws EventStreamException if the platform fails
     */
    fun poll(timeout: Duration): List<StreamRecord>

    /**
     * Durably acknowledges every record delivered by [poll] so far. Does nothing if no records
     * were delivered since the last commit.
     *
     * @throws CommitRejectedException if the consumer no longer owns some of the delivered records;
     *   those records will be redelivered
     * @throws IllegalStateException if [subscribe] was never called, or the consumer is closed
     * @throws EventStreamException if the platform fails
     */
    fun commit()

    /**
     * Releases all platform resources. Does not commit. Calling it again has no effect. After
     * closing, [subscribe], [poll] and [commit] throw [IllegalStateException].
     *
     * @throws EventStreamException if the platform fails while closing
     */
    override fun close()
}
