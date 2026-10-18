package dev.reichling.adapter.kafka

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.assertThrows

class KafkaConsumerSettingsTest {
    private fun settings(
        bootstrapServers: String = "localhost:9092",
        groupId: String = "group",
        maxPollRecords: Int? = null,
        additionalProperties: Map<String, String> = emptyMap(),
    ) = KafkaConsumerSettings(
        bootstrapServers = bootstrapServers,
        groupId = groupId,
        maxPollRecords = maxPollRecords,
        additionalProperties = additionalProperties,
    )

    @Test
    fun `blank groupId is rejected`() {
        val error = assertThrows<IllegalArgumentException> { settings(groupId = "  ") }
        assertTrue(error.message!!.contains("groupId"))
    }

    @Test
    fun `blank bootstrapServers is rejected`() {
        val error = assertThrows<IllegalArgumentException> { settings(bootstrapServers = "") }
        assertTrue(error.message!!.contains("bootstrapServers"))
    }

    @Test
    fun `non-positive maxPollRecords is rejected`() {
        assertThrows<IllegalArgumentException> { settings(maxPollRecords = 0) }
        assertThrows<IllegalArgumentException> { settings(maxPollRecords = -5) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "enable.auto.commit",
            "key.deserializer",
            "value.deserializer",
            "bootstrap.servers",
            "group.id",
            "auto.offset.reset",
        ],
    )
    fun `adapter-owned keys in additionalProperties are rejected and named`(key: String) {
        val error = assertThrows<IllegalArgumentException> {
            settings(additionalProperties = mapOf(key to "x"))
        }
        assertTrue(error.message!!.contains(key), "message should name '$key' but was: ${error.message}")
    }

    @Test
    fun `additional properties reach the client configuration unchanged`() {
        val properties = settings(additionalProperties = mapOf("session.timeout.ms" to "20000")).toProperties()

        assertEquals("20000", properties["session.timeout.ms"])
    }

    @Test
    fun `effective configuration has auto-commit off and byte-array deserializers`() {
        val properties = settings().toProperties()

        assertEquals(false, properties["enable.auto.commit"])
        assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer", properties["key.deserializer"])
        assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer", properties["value.deserializer"])
    }

    @Test
    fun `auto offset reset defaults to earliest`() {
        assertEquals("earliest", settings().toProperties()["auto.offset.reset"])
        assertEquals(
            "latest",
            KafkaConsumerSettings("localhost:9092", "group", autoOffsetReset = AutoOffsetReset.LATEST)
                .toProperties()["auto.offset.reset"],
        )
    }

    @Test
    fun `typed settings are written to the configuration`() {
        val properties = KafkaConsumerSettings(
            bootstrapServers = "broker:9092",
            groupId = "g",
            clientId = "metamorph-1",
            maxPollRecords = 50,
        ).toProperties()

        assertEquals("broker:9092", properties["bootstrap.servers"])
        assertEquals("g", properties["group.id"])
        assertEquals("metamorph-1", properties["client.id"])
        assertEquals(50, properties["max.poll.records"])
    }

    @Test
    fun `optional settings are omitted when not set`() {
        val properties = settings().toProperties()

        assertFalse(properties.containsKey("client.id"))
        assertFalse(properties.containsKey("max.poll.records"))
    }
}
