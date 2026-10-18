package dev.reichling.core.model

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamRecordTest {
    private val timestamp = Instant.parse("2026-10-18T10:00:00Z")
    private val origin = RecordOrigin("orders", 0, 7)

    private fun record(
        key: ByteArray? = "k".toByteArray(),
        value: ByteArray? = "v".toByteArray(),
        headers: List<StreamRecord.Header> = listOf(StreamRecord.Header("trace", "a".toByteArray())),
        timestamp: Instant = this.timestamp,
        origin: RecordOrigin = this.origin,
    ) = StreamRecord(key, value, headers, timestamp, origin)

    @Test
    fun `records built from separate but identical byte arrays are equal`() {
        val a = record()
        val b = record()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `null key and null value are equal to each other and differ from empty arrays`() {
        val tombstone = record(key = null, value = null)

        assertEquals(tombstone, record(key = null, value = null))
        assertEquals(tombstone.hashCode(), record(key = null, value = null).hashCode())
        assertNotEquals(tombstone, record(key = ByteArray(0), value = null))
        assertNotEquals(tombstone, record(key = null, value = ByteArray(0)))
    }

    @Test
    fun `headers are compared by key and content, in order`() {
        val headers = listOf(
            StreamRecord.Header("trace", "a".toByteArray()),
            StreamRecord.Header("tag", null),
            StreamRecord.Header("trace", "b".toByteArray()),
        )
        val same = listOf(
            StreamRecord.Header("trace", "a".toByteArray()),
            StreamRecord.Header("tag", null),
            StreamRecord.Header("trace", "b".toByteArray()),
        )

        assertEquals(record(headers = headers), record(headers = same))
        assertEquals(record(headers = headers).hashCode(), record(headers = same).hashCode())
        assertNotEquals(record(headers = headers), record(headers = same.reversed()))
        assertNotEquals(
            record(headers = listOf(StreamRecord.Header("tag", null))),
            record(headers = listOf(StreamRecord.Header("tag", ByteArray(0)))),
        )
    }

    @Test
    fun `records differ when any field differs`() {
        val base = record()

        assertNotEquals(base, record(key = "other".toByteArray()))
        assertNotEquals(base, record(value = "other".toByteArray()))
        assertNotEquals(base, record(headers = emptyList()))
        assertNotEquals(base, record(timestamp = timestamp.plusSeconds(1)))
        assertNotEquals(base, record(origin = RecordOrigin("orders", 0, 8)))
    }

    @Test
    fun `toString shows sizes instead of payload bytes`() {
        val text = record(key = "secret-key".toByteArray(), value = null).toString()

        assertFalse(text.contains("secret-key"))
        assertTrue(text.contains("key=10B"))
        assertTrue(text.contains("value=null"))
    }
}
