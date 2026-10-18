package dev.reichling.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CoreIsolationTest {
    @Test
    fun `no Kafka class is loadable from core`() {
        assertThrows<ClassNotFoundException> {
            Class.forName("org.apache.kafka.clients.consumer.Consumer")
        }
    }
}
