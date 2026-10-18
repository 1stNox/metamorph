## ADDED Requirements

### Requirement: Platform-neutral stream record
The system SHALL represent every consumed event as a `StreamRecord`, a type defined in the `core` module. A `StreamRecord` SHALL contain:
- an optional key, as raw bytes
- an optional value, as raw bytes
- an ordered list of headers. Each header has a string key and an optional raw-byte value, and duplicate keys are allowed.
- a timestamp, as an `Instant`
- an origin: stream name, partition number, and offset

A `StreamRecord` SHALL NOT decode the key or the value. Two `StreamRecord`s SHALL be equal when all of their fields are equal, and byte arrays SHALL be compared by content.

#### Scenario: Tombstone record keeps its absent value
- **WHEN** a record whose value is absent (a tombstone) is consumed
- **THEN** the delivered `StreamRecord` has a `null` value, not an empty byte array

#### Scenario: Duplicate header keys are preserved in order
- **WHEN** a record with headers `trace=a`, `tag=x`, `trace=b` is consumed
- **THEN** the delivered `StreamRecord` has exactly those three headers, in that order

#### Scenario: Records compare by content
- **WHEN** two `StreamRecord` instances are built from separate byte arrays with identical contents and identical other fields
- **THEN** they are equal and have the same hash code

### Requirement: Core is independent of streaming platforms
The `core` module SHALL define the `EventStreamConsumer` port and its supporting types. It SHALL NOT depend on any event-streaming client library. The port's method signatures SHALL use only types from `core`, the Kotlin standard library, or the JDK.

#### Scenario: Kafka client is not on the core classpath
- **WHEN** the `core` module's tests run
- **THEN** no class from the `org.apache.kafka` package can be loaded

### Requirement: Stream subscription
An `EventStreamConsumer` SHALL consume only from the streams passed to `subscribe`. A later `subscribe` call SHALL replace the previous subscription. `subscribe` SHALL reject an empty set of stream names with an `IllegalArgumentException`. Calling `poll` before any `subscribe` SHALL fail with an `IllegalStateException`.

#### Scenario: Only subscribed streams are consumed
- **WHEN** records exist in streams `orders` and `payments`, and the consumer subscribes to `orders` only
- **THEN** `poll` delivers only records whose origin stream is `orders`

#### Scenario: Empty subscription is rejected
- **WHEN** `subscribe` is called with an empty set
- **THEN** it throws `IllegalArgumentException`

#### Scenario: Polling without a subscription is rejected
- **WHEN** `poll` is called on a consumer that has never subscribed
- **THEN** it throws `IllegalStateException`

### Requirement: Bounded polling with per-partition ordering
`poll(timeout)` SHALL return the records that are available within `timeout`. If none arrive within `timeout`, it SHALL return an empty list. Records from the same stream partition SHALL be delivered in ascending offset order, both within one `poll` and across consecutive `poll` calls.

#### Scenario: No records within the timeout
- **WHEN** `poll` is called with a timeout of 500 ms on a subscribed stream that receives no records
- **THEN** it returns an empty list

#### Scenario: Per-partition order is preserved
- **WHEN** records with offsets 0 to 99 are produced to one partition and consumed across several `poll` calls
- **THEN** the records are delivered in offset order 0 to 99, without gaps or duplicates

### Requirement: At-least-once delivery through explicit commit
A record SHALL count as processed only after the caller calls `commit()`. `commit()` SHALL durably acknowledge every record that earlier `poll` calls delivered on this consumer. When a consumer in the same consumer group starts later, it SHALL resume after the last acknowledged record on each partition. The consumer SHALL NOT acknowledge records on its own: not periodically, not on `close`, and not when partitions are reassigned. Calling `commit()` when no records are unacknowledged SHALL do nothing.

#### Scenario: Committed records are not redelivered
- **WHEN** a consumer polls records 0 to 9, calls `commit()`, and closes, and a new consumer in the same group subscribes to the same stream
- **THEN** the new consumer's first delivered record on that partition is record 10

#### Scenario: Uncommitted records are redelivered
- **WHEN** a consumer polls records 0 to 9 and closes without calling `commit()`, and a new consumer in the same group subscribes to the same stream
- **THEN** the new consumer delivers records 0 to 9 again

#### Scenario: Commit with nothing pending
- **WHEN** `commit()` is called on a consumer that has not delivered any records since its last commit
- **THEN** it returns without error and acknowledges nothing

### Requirement: Consumer lifecycle and thread confinement
An `EventStreamConsumer` SHALL be `AutoCloseable`. `close()` SHALL release all platform resources and SHALL be idempotent. Calling `subscribe`, `poll`, or `commit` after `close()` SHALL fail with an `IllegalStateException`. An `EventStreamConsumer` is not thread-safe: all calls, including `close()`, SHALL come from a single thread, and the KDoc of the port SHALL state this contract. To shut down, the owning thread stops its poll loop, which `poll`'s bounded timeout makes possible, and then calls `close()`.

#### Scenario: Closing twice is harmless
- **WHEN** `close()` is called twice on the same consumer
- **THEN** the second call returns without error

#### Scenario: Use after close is rejected
- **WHEN** `poll` is called on a closed consumer
- **THEN** it throws `IllegalStateException`

### Requirement: Platform-neutral error reporting
A consumer SHALL report failures from the streaming platform as `EventStreamException`, a type defined in `core`, with the original platform exception as its cause. If a commit is rejected because the consumer no longer owns some of the delivered records, the consumer SHALL throw `CommitRejectedException`, a subtype of `EventStreamException`. That exception signals that those records will be redelivered. Programming errors such as `IllegalArgumentException` and `IllegalStateException` SHALL NOT be wrapped.

#### Scenario: Platform failure during poll
- **WHEN** the platform client fails during `poll`, for example with an authorization error
- **THEN** `poll` throws `EventStreamException`, and the platform error is its cause

#### Scenario: Commit rejected after losing ownership
- **WHEN** `commit()` fails because the consumer group rebalanced and the records belong to another consumer
- **THEN** `commit()` throws `CommitRejectedException`
