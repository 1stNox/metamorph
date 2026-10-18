package dev.reichling.adapter.kafka

import dev.reichling.core.model.RecordOrigin
import dev.reichling.core.port.CommitRejectedException
import dev.reichling.core.port.EventStreamException
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.RebalanceInProgressException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KafkaConsumerAdapterTest {
    private val orders0 = TopicPartition("orders", 0)
    private val orders1 = TopicPartition("orders", 1)

    private val fixture = MockConsumerFixture()
    private val adapter = fixture.adapter

    @Nested
    inner class Mapping {
        @Test
        fun `origin, key, value and timestamp are mapped`() {
            fixture.subscribeAndAssign(setOf("orders"), orders1)
            fixture.addRecord(orders1, 0, key = "k".toByteArray(), value = "v".toByteArray())
            fixture.addRecord(orders1, 1, key = "k".toByteArray(), value = "v".toByteArray(), timestamp = 1_700_000_000_456L)

            val records = adapter.poll(100.milliseconds)

            assertEquals(2, records.size)
            val record = records[1]
            assertEquals(RecordOrigin("orders", 1, 1), record.origin)
            assertEquals("k", record.key!!.decodeToString())
            assertEquals("v", record.value!!.decodeToString())
            assertEquals(Instant.ofEpochMilli(1_700_000_000_456L), record.timestamp)
        }

        @Test
        fun `a tombstone keeps its null value and a missing key stays null`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            fixture.addRecord(orders0, 0, key = "k".toByteArray(), value = null)
            fixture.addRecord(orders0, 1, key = null, value = "v".toByteArray())

            val (tombstone, keyless) = adapter.poll(100.milliseconds)

            assertNull(tombstone.value)
            assertEquals("k", tombstone.key!!.decodeToString())
            assertNull(keyless.key)
        }

        @Test
        fun `duplicate headers keep their order, and null header values stay null`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            fixture.addRecord(
                orders0,
                0,
                value = "v".toByteArray(),
                headers = listOf(
                    "trace" to "a".toByteArray(),
                    "tag" to "x".toByteArray(),
                    "trace" to "b".toByteArray(),
                    "empty" to null,
                ),
            )

            val headers = adapter.poll(100.milliseconds).single().headers

            assertEquals(listOf("trace", "tag", "trace", "empty"), headers.map { it.key })
            assertEquals(listOf("a", "x", "b", null), headers.map { it.value?.decodeToString() })
        }

        @Test
        fun `records are returned in offset order per partition`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0, orders1)
            fixture.addRecords(orders0, 0L..4L)
            fixture.addRecords(orders1, 0L..2L)

            val records = adapter.poll(100.milliseconds)

            assertEquals(8, records.size)
            assertEquals((0L..4L).toList(), records.filter { it.origin.partition == 0 }.map { it.origin.offset })
            assertEquals((0L..2L).toList(), records.filter { it.origin.partition == 1 }.map { it.origin.offset })
        }

        @Test
        fun `poll without records returns an empty list`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)

            assertEquals(emptyList<Any>(), adapter.poll(10.milliseconds))
        }
    }

    @Nested
    inner class Commit {
        @Test
        fun `commit acknowledges the highest delivered offset plus one per partition`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0, orders1)
            fixture.addRecords(orders0, 0L..4L)
            fixture.addRecords(orders1, 0L..2L)
            adapter.poll(100.milliseconds)

            adapter.commit()

            assertEquals(5L, fixture.committedOffset(orders0))
            assertEquals(3L, fixture.committedOffset(orders1))
        }

        @Test
        fun `commit covers records of all polls since the last commit`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            fixture.addRecords(orders0, 0L..2L)
            adapter.poll(100.milliseconds)
            fixture.addRecords(orders0, 3L..5L)
            adapter.poll(100.milliseconds)

            adapter.commit()

            assertEquals(6L, fixture.committedOffset(orders0))
        }

        @Test
        fun `a second commit with nothing pending does not call the client`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            fixture.addRecords(orders0, 0L..4L)
            adapter.poll(100.milliseconds)

            adapter.commit()
            adapter.commit()

            assertEquals(1, fixture.mock.commitSyncCalls)
        }

        @Test
        fun `commit without any delivered record does not call the client`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            adapter.poll(10.milliseconds)

            adapter.commit()

            assertEquals(0, fixture.mock.commitSyncCalls)
        }

        @Test
        fun `a partition without new records is not committed again`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0, orders1)
            fixture.addRecords(orders0, 0L..1L)
            fixture.addRecords(orders1, 0L..1L)
            adapter.poll(100.milliseconds)
            adapter.commit()

            fixture.addRecords(orders0, 2L..3L)
            adapter.poll(100.milliseconds)
            adapter.commit()

            assertEquals(4L, fixture.committedOffset(orders0))
            assertEquals(2L, fixture.committedOffset(orders1))
        }

        @Test
        fun `a failed commit keeps the offsets so it can be retried`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            fixture.addRecords(orders0, 0L..4L)
            adapter.poll(100.milliseconds)
            fixture.mock.commitFailure = CommitFailedException()

            assertThrows<CommitRejectedException> { adapter.commit() }
            fixture.mock.commitFailure = null
            adapter.commit()

            assertEquals(5L, fixture.committedOffset(orders0))
        }

        @Test
        fun `the adapter never commits on its own`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            fixture.addRecords(orders0, 0L..4L)
            adapter.poll(100.milliseconds)
            fixture.addRecords(orders0, 5L..6L)
            adapter.poll(100.milliseconds)

            assertEquals(0, fixture.mock.commitSyncCalls)
            assertNull(fixture.committedOffset(orders0))
        }
    }

    @Nested
    inner class Rebalance {
        @Test
        fun `a revoked partition is not committed`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0, orders1)
            fixture.addRecords(orders0, 0L..4L)
            fixture.addRecords(orders1, 0L..2L)
            adapter.poll(100.milliseconds)

            fixture.mock.schedulePollTask { fixture.mock.rebalance(listOf(orders1)) }
            adapter.poll(100.milliseconds)
            adapter.commit()

            assertNull(fixture.committedOffset(orders0))
            assertEquals(3L, fixture.committedOffset(orders1))
        }

        @Test
        fun `when every delivered partition is revoked, commit does not call the client`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            fixture.addRecords(orders0, 0L..4L)
            adapter.poll(100.milliseconds)

            fixture.mock.updateBeginningOffsets(mapOf(orders1 to 0L))
            fixture.mock.schedulePollTask { fixture.mock.rebalance(listOf(orders1)) }
            adapter.poll(100.milliseconds)
            adapter.commit()

            assertEquals(0, fixture.mock.commitSyncCalls)
            assertNull(fixture.committedOffset(orders0))
        }

        @Test
        fun `a lost partition is not committed`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0, orders1)
            fixture.addRecords(orders0, 0L..4L)
            fixture.addRecords(orders1, 0L..2L)
            adapter.poll(100.milliseconds)

            val listener = fixture.mock.rebalanceListener!!
            fixture.mock.schedulePollTask { listener.onPartitionsLost(listOf(orders0)) }
            adapter.poll(100.milliseconds)
            adapter.commit()

            assertNull(fixture.committedOffset(orders0))
            assertEquals(3L, fixture.committedOffset(orders1))
        }

        @Test
        fun `a partition that is kept across a rebalance is still committed`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            fixture.addRecords(orders0, 0L..4L)
            adapter.poll(100.milliseconds)

            fixture.mock.schedulePollTask { fixture.mock.rebalance(listOf(orders0, orders1)) }
            fixture.mock.updateBeginningOffsets(mapOf(orders1 to 0L))
            adapter.poll(100.milliseconds)
            adapter.commit()

            assertEquals(5L, fixture.committedOffset(orders0))
        }
    }

    @Nested
    inner class Translation {
        @Test
        fun `a Kafka failure during poll becomes EventStreamException with the cause`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            val failure = KafkaException("not authorized")
            fixture.mock.setPollException(failure)

            val thrown = assertThrows<EventStreamException> { adapter.poll(100.milliseconds) }

            assertSame(failure, thrown.cause)
        }

        @Test
        fun `CommitFailedException becomes CommitRejectedException with the cause`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            fixture.addRecords(orders0, 0L..1L)
            adapter.poll(100.milliseconds)
            val failure = CommitFailedException()
            fixture.mock.commitFailure = failure

            val thrown = assertThrows<CommitRejectedException> { adapter.commit() }

            assertSame(failure, thrown.cause)
        }

        @Test
        fun `RebalanceInProgressException becomes CommitRejectedException`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            fixture.addRecords(orders0, 0L..1L)
            adapter.poll(100.milliseconds)
            val failure = RebalanceInProgressException("rebalancing")
            fixture.mock.commitFailure = failure

            val thrown = assertThrows<CommitRejectedException> { adapter.commit() }

            assertSame(failure, thrown.cause)
        }

        @Test
        fun `any other Kafka failure during commit is a plain EventStreamException`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            fixture.addRecords(orders0, 0L..1L)
            adapter.poll(100.milliseconds)
            fixture.mock.commitFailure = KafkaException("broker unavailable")

            val thrown = assertThrows<EventStreamException> { adapter.commit() }

            assertTrue(thrown !is CommitRejectedException)
        }

        @Test
        fun `programming errors pass through unwrapped`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            fixture.addRecords(orders0, 0L..1L)
            adapter.poll(100.milliseconds)
            val failure = IllegalStateException("bug")
            fixture.mock.commitFailure = failure

            assertSame(failure, assertThrows<IllegalStateException> { adapter.commit() })
        }
    }

    @Nested
    inner class Lifecycle {
        @Test
        fun `poll before subscribe is rejected`() {
            assertThrows<IllegalStateException> { adapter.poll(10.milliseconds) }
        }

        @Test
        fun `commit before subscribe is rejected`() {
            assertThrows<IllegalStateException> { adapter.commit() }
        }

        @Test
        fun `an empty subscription is rejected`() {
            assertThrows<IllegalArgumentException> { adapter.subscribe(emptySet()) }
        }

        @Test
        fun `a later subscribe replaces the previous subscription`() {
            adapter.subscribe(setOf("orders"))
            adapter.subscribe(setOf("payments"))

            assertEquals(setOf("payments"), fixture.mock.subscription())
        }

        @Test
        fun `closing twice is a no-op`() {
            adapter.subscribe(setOf("orders"))

            adapter.close()
            adapter.close()

            assertTrue(fixture.mock.closed())
        }

        @Test
        fun `subscribe, poll and commit after close are rejected`() {
            adapter.subscribe(setOf("orders"))
            adapter.close()

            assertThrows<IllegalStateException> { adapter.subscribe(setOf("orders")) }
            assertThrows<IllegalStateException> { adapter.poll(10.milliseconds) }
            assertThrows<IllegalStateException> { adapter.commit() }
        }

        @Test
        fun `close does not commit pending records`() {
            fixture.subscribeAndAssign(setOf("orders"), orders0)
            fixture.addRecords(orders0, 0L..4L)
            adapter.poll(100.milliseconds)

            adapter.close()

            assertEquals(0, fixture.mock.commitSyncCalls)
            assertTrue(fixture.mock.closed())
        }

        @Test
        fun `close uses the configured timeout`() {
            adapter.close()

            assertEquals(3.seconds.inWholeMilliseconds, fixture.mock.closeOptions!!.timeout().get().toMillis())
        }
    }
}
