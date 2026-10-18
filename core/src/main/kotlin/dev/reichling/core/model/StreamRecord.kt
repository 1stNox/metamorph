package dev.reichling.core.model

import java.time.Instant

/**
 * A platform-neutral event consumed from a stream.
 *
 * Key, value and header values are raw bytes: decoding (JSON, Avro, ...) is a separate concern.
 * `null` means "absent", which is different from an empty array (a Kafka tombstone has a `null` value).
 *
 * This is a regular class rather than a data class because `ByteArray` properties would be compared
 * by reference in a generated `equals`. Byte arrays are compared by content here.
 *
 * @property key optional record key
 * @property value optional record value
 * @property headers ordered headers; duplicate keys are allowed
 * @property timestamp record timestamp
 * @property origin stream, partition and offset the record was read from
 */
class StreamRecord(
    val key: ByteArray?,
    val value: ByteArray?,
    val headers: List<Header>,
    val timestamp: Instant,
    val origin: RecordOrigin,
) {
    /**
     * A record header.
     *
     * @property key header name
     * @property value optional raw header value
     */
    class Header(
        val key: String,
        val value: ByteArray?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Header) return false
            return key == other.key && value.contentEquals(other.value)
        }

        override fun hashCode(): Int = 31 * key.hashCode() + value.contentHashCode()

        override fun toString(): String = "Header(key=$key, value=${value.sizeLabel()})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamRecord) return false
        return key.contentEquals(other.key) &&
            value.contentEquals(other.value) &&
            headers == other.headers &&
            timestamp == other.timestamp &&
            origin == other.origin
    }

    override fun hashCode(): Int {
        var result = key.contentHashCode()
        result = 31 * result + value.contentHashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + origin.hashCode()
        return result
    }

    /** Shows sizes instead of payload bytes, so records are safe and short to log. */
    override fun toString(): String =
        "StreamRecord(origin=$origin, timestamp=$timestamp, key=${key.sizeLabel()}, " +
            "value=${value.sizeLabel()}, headers=$headers)"
}

private fun ByteArray?.sizeLabel(): String = if (this == null) "null" else "${size}B"
