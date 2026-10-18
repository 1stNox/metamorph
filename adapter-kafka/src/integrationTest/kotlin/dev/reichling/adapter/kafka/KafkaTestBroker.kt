package dev.reichling.adapter.kafka

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.testcontainers.kafka.KafkaContainer
import java.util.UUID

/**
 * One Kafka broker per test JVM, started on first use. Tests isolate themselves with random topic
 * and group names instead of restarting the broker.
 *
 * Testcontainers removes the container when the JVM exits normally. If the JVM is killed, a
 * container can be left behind; see the README for how to clean it up.
 */
object KafkaTestBroker {
    private const val IMAGE = "apache/kafka-native:4.3.1"

    private val container: KafkaContainer by lazy {
        KafkaContainer(IMAGE).also { it.start() }
    }

    val bootstrapServers: String
        get() = container.bootstrapServers

    /** A topic name that no other test uses. */
    fun uniqueName(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    /** Creates a topic with an explicit partition count (auto-creation would give a single partition). */
    fun createTopic(
        name: String,
        partitions: Int = 1,
    ) {
        Admin.create(mapOf("bootstrap.servers" to bootstrapServers)).use { admin ->
            admin.createTopics(listOf(NewTopic(name, partitions, 1.toShort()))).all().get()
        }
    }

    fun record(
        topic: String,
        key: String?,
        value: String?,
        headers: List<Pair<String, String?>> = emptyList(),
        partition: Int? = null,
    ): ProducerRecord<ByteArray, ByteArray> =
        ProducerRecord(
            topic,
            partition,
            key?.toByteArray(),
            value?.toByteArray(),
            headers.map { (name, headerValue) -> RecordHeader(name, headerValue?.toByteArray()) },
        )

    /** Produces [records] in order and waits until the broker has acknowledged all of them. */
    fun produce(records: List<ProducerRecord<ByteArray, ByteArray>>) {
        val config =
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java.name,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java.name,
            )
        KafkaProducer<ByteArray, ByteArray>(config).use { producer ->
            records.map { producer.send(it) }.forEach { it.get() }
        }
    }

    fun produce(vararg records: ProducerRecord<ByteArray, ByteArray>) = produce(records.toList())
}
