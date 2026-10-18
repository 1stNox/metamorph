package dev.reichling.adapter.kafka

import dev.reichling.core.model.RecordOrigin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** Runs the adapter against a real broker. Each test uses its own topics and consumer group. */
class KafkaConsumerAdapterIntegrationTest {
    @Test
    fun `maps key, value, header and origin from a real broker`() {
        val topic = KafkaTestBroker.uniqueName("orders")
        KafkaTestBroker.createTopic(topic, partitions = 3)
        KafkaTestBroker.produce(
            KafkaTestBroker.record(topic, "k1", "v1", headers = listOf("source" to "test"), partition = 2),
        )

        KafkaConsumerAdapter(testSettings()).use { adapter ->
            adapter.subscribe(setOf(topic))
            val record = pollUntil(adapter, 1).single()

            assertEquals("k1", record.key!!.decodeToString())
            assertEquals("v1", record.value!!.decodeToString())
            assertEquals(listOf("source"), record.headers.map { it.key })
            assertEquals(
                "test",
                record.headers
                    .single()
                    .value!!
                    .decodeToString(),
            )
            assertEquals(RecordOrigin(topic, partition = 2, offset = 0), record.origin)
        }
    }

    @Test
    fun `a tombstone arrives with a null value`() {
        val topic = KafkaTestBroker.uniqueName("orders")
        KafkaTestBroker.createTopic(topic)
        KafkaTestBroker.produce(KafkaTestBroker.record(topic, "k1", null))

        KafkaConsumerAdapter(testSettings()).use { adapter ->
            adapter.subscribe(setOf(topic))
            val record = pollUntil(adapter, 1).single()

            assertEquals("k1", record.key!!.decodeToString())
            assertEquals(null, record.value)
        }
    }

    @Test
    fun `a new group with the default reset receives pre-existing records`() {
        val topic = KafkaTestBroker.uniqueName("orders")
        KafkaTestBroker.createTopic(topic)
        KafkaTestBroker.produce((0..4).map { KafkaTestBroker.record(topic, "k$it", "v$it") })

        KafkaConsumerAdapter(testSettings()).use { adapter ->
            adapter.subscribe(setOf(topic))
            val records = pollUntil(adapter, 5)

            assertEquals((0L..4L).toList(), records.map { it.origin.offset })
        }
    }

    @Test
    fun `only subscribed streams are delivered`() {
        val orders = KafkaTestBroker.uniqueName("orders")
        val payments = KafkaTestBroker.uniqueName("payments")
        KafkaTestBroker.createTopic(orders)
        KafkaTestBroker.createTopic(payments)
        KafkaTestBroker.produce(
            (0..2).map { KafkaTestBroker.record(orders, "o$it", "order") } +
                (0..2).map { KafkaTestBroker.record(payments, "p$it", "payment") },
        )

        KafkaConsumerAdapter(testSettings()).use { adapter ->
            adapter.subscribe(setOf(orders))
            val records = pollUntil(adapter, 3) + adapter.poll(500.milliseconds)

            assertEquals(3, records.size)
            assertTrue(records.all { it.origin.stream == orders }, "records: ${records.map { it.origin }}")
        }
    }

    @Test
    fun `100 records on one partition arrive in order without gaps or duplicates`() {
        val topic = KafkaTestBroker.uniqueName("orders")
        KafkaTestBroker.createTopic(topic, partitions = 1)
        KafkaTestBroker.produce((0 until 100).map { KafkaTestBroker.record(topic, "k$it", "v$it") })

        KafkaConsumerAdapter(testSettings()).use { adapter ->
            adapter.subscribe(setOf(topic))
            val records = pollUntil(adapter, 100) + adapter.poll(300.milliseconds)

            assertEquals((0L until 100L).toList(), records.map { it.origin.offset })
            assertEquals((0 until 100).map { "v$it" }, records.map { it.value!!.decodeToString() })
        }
    }

    @Test
    fun `poll on an empty topic returns an empty list`() {
        val topic = KafkaTestBroker.uniqueName("empty")
        KafkaTestBroker.createTopic(topic)

        KafkaConsumerAdapter(testSettings()).use { adapter ->
            adapter.subscribe(setOf(topic))

            assertEquals(emptyList<Any>(), adapter.poll(500.milliseconds))
        }
    }

    @Test
    fun `committed records are not redelivered to a restarted consumer`() {
        val topic = KafkaTestBroker.uniqueName("orders")
        KafkaTestBroker.createTopic(topic)
        KafkaTestBroker.produce((0 until 10).map { KafkaTestBroker.record(topic, "k$it", "v$it") })
        val group = KafkaTestBroker.uniqueName("group")

        KafkaConsumerAdapter(testSettings(group)).use { adapter ->
            adapter.subscribe(setOf(topic))
            assertEquals(10, pollUntil(adapter, 10).size)
            adapter.commit()
        }

        KafkaTestBroker.produce((10 until 15).map { KafkaTestBroker.record(topic, "k$it", "v$it") })

        KafkaConsumerAdapter(testSettings(group)).use { adapter ->
            adapter.subscribe(setOf(topic))
            val records = pollUntil(adapter, 5) + adapter.poll(300.milliseconds)

            assertEquals((10L..14L).toList(), records.map { it.origin.offset })
        }
    }

    @Test
    fun `uncommitted records are redelivered to a restarted consumer`() {
        val topic = KafkaTestBroker.uniqueName("orders")
        KafkaTestBroker.createTopic(topic)
        KafkaTestBroker.produce((0 until 10).map { KafkaTestBroker.record(topic, "k$it", "v$it") })
        val group = KafkaTestBroker.uniqueName("group")

        KafkaConsumerAdapter(testSettings(group)).use { adapter ->
            adapter.subscribe(setOf(topic))
            assertEquals(10, pollUntil(adapter, 10).size)
            // closes without commit
        }

        KafkaConsumerAdapter(testSettings(group)).use { adapter ->
            adapter.subscribe(setOf(topic))
            val records = pollUntil(adapter, 10)

            assertEquals((0L..9L).toList(), records.map { it.origin.offset })
        }
    }

    @Test
    fun `commit with nothing pending succeeds against a real broker`() {
        val topic = KafkaTestBroker.uniqueName("orders")
        KafkaTestBroker.createTopic(topic)

        KafkaConsumerAdapter(testSettings()).use { adapter ->
            adapter.subscribe(setOf(topic))
            adapter.poll(200.milliseconds)

            adapter.commit()
        }
    }
}
