## Why

Metamorph's pipeline is consume → transform → enrich → write. Consuming comes first. Kafka is the first platform we target, but others will follow (Redpanda, Event Hubs, Pulsar, Kinesis). If the pipeline core calls `KafkaConsumer` directly, every new platform means rewriting the core.

We will use a hexagonal ("ports and adapters") layout. The core defines a small **port** interface for consuming event streams, and each platform gets an **adapter** that implements it. This is the classic [Adapter pattern](https://refactoring.guru/design-patterns/adapter): the core is the client, the port is the client interface, and Kafka's `Consumer` is the adapted service. The repository is still the Gradle init scaffold, so now is the cheapest time to set up this structure.

## What Changes

- Split the build into Gradle modules that enforce the hexagonal dependency rule:
  - `core`: domain model and ports, with no infrastructure dependencies
  - `adapter-kafka`: the Kafka implementation of the consumer port
  - `app`: the existing module, kept as the future composition root. `App.kt` moves into `dev.reichling.app`, so every part of the hexagon has its own package root.
- Add a platform-neutral `StreamRecord` domain type. It carries key, value, headers, timestamp, and origin as raw bytes and plain values. Decoding formats such as JSON or Avro is not part of consuming.
- Add an `EventStreamConsumer` port (`subscribe`, `poll`, `commit`, `close`) with at-least-once semantics. A record counts as processed only when the core calls `commit()`, so the core can commit after the transform and write steps succeed.
- Add a port-level exception type, so the core never has to catch Kafka exceptions.
- Add `KafkaConsumerAdapter`, which wraps Kafka's `Consumer<ByteArray, ByteArray>`:
  - typed settings, with auto-commit and the byte-array deserializers fixed by the adapter
  - mapping from `ConsumerRecord` to `StreamRecord`
  - explicit offset tracking and commits
  - rebalance handling, so a rebalance never commits records that were not processed
  - translation of Kafka exceptions into port exceptions
- Add consumer tests at two levels:
  - fast unit tests against Kafka's `MockConsumer`
  - integration tests against a real broker started with **Testcontainers**, in a separate `integrationTest` suite
- Add an **architecture verification layer**: a test-only `architecture-test` module whose [Konsist](https://docs.konsist.lemonappdev.com/) tests check the hexagonal rules across the production sources of all modules. They run as part of `./gradlew test`, and they check:
  - the dependency direction between layers
  - that `core` stays pure and adapters stay isolated from each other
  - port and adapter conventions
  - that no platform types appear in an adapter's public API

  Gradle module boundaries only stop imports between modules. These tests also cover rules inside a module, naming and documentation conventions, and someone loosening a module's dependencies by mistake.

## Capabilities

### New Capabilities
- `event-stream-consumer`: the platform-agnostic contract for consuming event streams. It covers the `StreamRecord` model, the consumer port's lifecycle, delivery and commit semantics (at-least-once), thread confinement, and error reporting. Every future consumer adapter must meet it.
- `kafka-consumer-adapter`: the Kafka implementation of that contract. It covers configuration and enforced settings, record mapping, offset commits, rebalance behavior, and exception translation.
- `hexagonal-architecture`: the structural rules of the codebase, checked by automated architecture tests. It covers layer dependencies, the purity of `core`, adapter isolation, port and adapter conventions, and platform-free public APIs for adapters. Later changes that add ports or adapters (sinks, enrichment sources) extend it.

### Modified Capabilities
<!-- None: no specs exist yet. -->

## Impact

- **Build**:
  - `settings.gradle.kts` includes the new `core`, `adapter-kafka`, and `architecture-test` modules.
  - Shared Kotlin/JVM settings move into a convention plugin, so each module stays small.
  - `gradle/libs.versions.toml` gains `kafka-clients`, `testcontainers-kafka`, `slf4j-simple` (test runtime), and `konsist` (used only by `architecture-test`).
- **Code**: new packages `dev.reichling.core.*` and `dev.reichling.adapter.kafka.*`. `App.kt` moves from `dev.reichling` to `dev.reichling.app`, and the `mainClass` changes with it. `app` does not change behavior yet; it is not wired to the consumer.
- **Developer environment**: integration tests need a Docker-compatible runtime. On machines using Podman, Testcontainers has to be pointed at the Podman socket and Ryuk has to be disabled.
- **Out of scope** for this change:
  - the pipeline runtime loop and wiring in `app`
  - configuration file format and loading
  - payload decoding
  - transformation, enrichment, and sinks
  - retries and dead-letter handling
  - consumer adapters for other platforms
