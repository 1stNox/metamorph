## ADDED Requirements

### Requirement: Kafka adapter implements the consumer port
The system SHALL provide `KafkaConsumerAdapter` in the `adapter-kafka` module. It SHALL implement `EventStreamConsumer` by delegating to Kafka's `Consumer<ByteArray, ByteArray>` client and SHALL meet every requirement of the `event-stream-consumer` capability. It SHALL map Kafka topics to stream names, Kafka partitions to partitions, and Kafka offsets to offsets.

#### Scenario: End-to-end consumption from a real broker
- **WHEN** a record with key `k1`, value `v1`, and header `source=test` is produced to topic `orders` on a Kafka broker, and the adapter subscribes to `orders` and polls
- **THEN** it delivers one `StreamRecord` with key `k1`, value `v1`, header `source=test`, origin stream `orders`, and the record's partition and offset

### Requirement: Typed Kafka consumer settings
The adapter SHALL be configured with a `KafkaConsumerSettings` value containing:
- `bootstrapServers`: required, non-blank
- `groupId`: required, non-blank
- `clientId`: optional
- `autoOffsetReset`: `EARLIEST` or `LATEST`, default `EARLIEST`
- `maxPollRecords`: optional, positive
- `additionalProperties`: a map of further Kafka consumer properties, passed to the client unchanged

The settings SHALL be validated when they are constructed.

#### Scenario: Blank group id is rejected
- **WHEN** `KafkaConsumerSettings` is built with a blank `groupId`
- **THEN** construction fails with `IllegalArgumentException`

#### Scenario: New consumer group starts from the earliest record
- **WHEN** records 0 to 4 already exist on a topic, and an adapter with a new `groupId` and the default `autoOffsetReset` subscribes and polls
- **THEN** it delivers records 0 to 4

#### Scenario: Additional properties reach the Kafka client
- **WHEN** `additionalProperties` contains `session.timeout.ms=20000`
- **THEN** the Kafka client configuration contains `session.timeout.ms=20000`

### Requirement: Adapter-owned Kafka properties cannot be overridden
The adapter SHALL always set:
- `enable.auto.commit=false`
- `ByteArrayDeserializer` as both key and value deserializer

If `additionalProperties` contains `enable.auto.commit`, `key.deserializer`, `value.deserializer`, `bootstrap.servers`, `group.id`, or `auto.offset.reset`, the settings SHALL be rejected with an `IllegalArgumentException` that names the conflicting property.

#### Scenario: Attempt to enable auto-commit
- **WHEN** `additionalProperties` contains `enable.auto.commit=true`
- **THEN** construction fails with `IllegalArgumentException`, and the message names `enable.auto.commit`

#### Scenario: Effective client configuration
- **WHEN** the adapter builds its Kafka client configuration from valid settings
- **THEN** the configuration has `enable.auto.commit=false` and `ByteArrayDeserializer` for keys and values

### Requirement: Synchronous per-partition offset commit
The adapter SHALL track, per topic partition, the highest offset it has delivered since the last successful commit. On `commit()`, it SHALL synchronously commit, for each tracked partition, that highest offset plus one. It SHALL NOT commit partitions that have no delivered records since the last commit. After a successful commit, it SHALL clear the tracked offsets.

#### Scenario: Commit covers each partition with delivered records
- **WHEN** the adapter delivered offsets 0 to 4 from `orders-0` and offsets 0 to 2 from `orders-1`, and then `commit()` is called
- **THEN** it commits offset 5 for `orders-0` and offset 3 for `orders-1`, and nothing else

#### Scenario: No broker call when nothing is pending
- **WHEN** `commit()` is called right after a successful commit, with no `poll` in between
- **THEN** the adapter does not call commit on the underlying Kafka client

### Requirement: Rebalance never commits unprocessed records
When Kafka revokes or loses partitions, the adapter SHALL drop the tracked offsets for those partitions without committing them. Records from those partitions that were delivered but not committed are then redelivered to the partitions' new owner.

#### Scenario: Revoked partition is not committed
- **WHEN** the adapter delivered offsets 0 to 4 from `orders-0`, then `orders-0` is revoked during a later `poll`, and then `commit()` is called
- **THEN** no offset is committed for `orders-0`

### Requirement: Kafka exceptions are translated
The adapter SHALL translate exceptions thrown by the Kafka client:
- `CommitFailedException` and `RebalanceInProgressException` thrown during `commit()` become `CommitRejectedException`.
- Any other `KafkaException` becomes `EventStreamException`.

In both cases the Kafka exception SHALL be the cause. No exception from the `org.apache.kafka` package SHALL escape the adapter's public methods.

#### Scenario: Poll failure is translated
- **WHEN** the underlying client throws a `KafkaException` from `poll`
- **THEN** the adapter throws `EventStreamException`, and the `KafkaException` is its cause

#### Scenario: Commit failure after rebalance is translated
- **WHEN** the underlying client throws `CommitFailedException` from `commitSync`
- **THEN** the adapter throws `CommitRejectedException`, and the `CommitFailedException` is its cause

### Requirement: Close releases the Kafka client without committing
`close()` SHALL close the underlying Kafka client with a bounded timeout, so the consumer leaves its group promptly. It SHALL NOT commit tracked offsets.

#### Scenario: Close does not commit pending records
- **WHEN** the adapter delivered offsets 0 to 4 from `orders-0` and `close()` is called without `commit()`
- **THEN** the underlying client is closed, and no offset is committed for `orders-0`
