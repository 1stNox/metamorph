## Context

Metamorph is meant to be a declarative stream-processing service: consume from an event streaming platform, transform, enrich from other sources, and write to a data store. The repository is still the Gradle `init` scaffold:
- one `app` module containing `dev.reichling.App`
- Kotlin 2.4.10, Gradle 9.8.1 with configuration cache enabled, JDK 25 toolchain
- JUnit Jupiter 6 and a version catalog

There is no framework (no Spring, Ktor, or DI container) and there is no production code yet.

This change starts the hexagonal architecture with its first port, consuming events, and its first adapter, Kafka. Later changes will add ports for transformation, enrichment sources, and sinks, so the structure set up here becomes the template for those.

The [Adapter pattern](https://refactoring.guru/design-patterns/adapter) maps onto the code like this:

| Pattern role | This change |
|---|---|
| Client | Metamorph core / pipeline runtime (a later change) |
| Client interface | `EventStreamConsumer` port, in `core` |
| Service (adaptee) | `org.apache.kafka.clients.consumer.Consumer<ByteArray, ByteArray>` |
| Adapter | `KafkaConsumerAdapter`, in `adapter-kafka`, which wraps the adaptee by composition |

Environment notes:
- `kafka-clients` 4.4.0 is the newest client.
- The newest released broker images are `apache/kafka(-native):4.3.1`; the 4.4.0 images are still release candidates.
- The development machine runs Podman with Docker emulation.

## Goals / Non-Goals

**Goals:**
- Enforce the hexagonal dependency rule (adapters depend on core; core depends on nothing) with Gradle module boundaries, not just conventions.
- Verify the architecture with automated tests that cover what module boundaries cannot: layer rules inside a module, port and adapter conventions, and platform-free adapter APIs. These tests guard against the boundaries being loosened by mistake.
- Define a small, platform-neutral consumer port that a Pulsar, Kinesis, or Event Hubs adapter could implement without changes to the port.
- Provide a production-quality Kafka adapter with at-least-once semantics and correct rebalance behavior.
- Cover the consumer at both test levels:
  - fast, Docker-free unit tests using `MockConsumer`
  - integration tests against a real broker using Testcontainers

**Non-Goals:**
- the pipeline runtime loop, threading model, and wiring in `app`
- configuration files, environment-variable binding, or a declarative model format
- payload decoding (JSON, Avro, Protobuf) and schema registries
- transformation, enrichment, and sink ports or adapters
- retry, dead-letter queues, error policies, exactly-once semantics, and transactions
- metrics and tracing
- adapters for platforms other than Kafka

## Decisions

### D1: Hexagonal boundaries as Gradle modules

```
metamorph/
├── build-logic/         convention plugin (shared Kotlin/JVM/test setup)
├── core/                dev.reichling.core.*            (no third-party runtime deps)
├── adapter-kafka/       dev.reichling.adapter.kafka.*   -> depends on :core, kafka-clients
├── app/                 dev.reichling.app.*             (composition root, later depends on both)
└── architecture-test/   test-only: Konsist rules over all modules' production sources (D11)
```

`App.kt` moves from the root package `dev.reichling` to `dev.reichling.app`. With the composition root in the root package, the App layer's package pattern (`dev.reichling..`) would also match every other layer, and a layer rule could not tell them apart.

Kafka is a dependency of `adapter-kafka` only, so `core` cannot import a Kafka type: the compiler rejects it. Each new platform adds an `adapter-<name>` module, and only the composition root (`app`) decides which adapters to include.

- *Alternative: a single module with package conventions, checked by ArchUnit or Konsist.* This is less build setup, but the rule is only checked at test time, and every adapter's dependencies end up on one classpath. Splitting modules later, once code exists, costs more than doing it now while the repository is empty.
- *Alternative: nest the adapters (`:adapters:kafka`).* The flat name `adapter-kafka` gives a unique jar name and is easier to type.

### D2: Shared build setup through a `build-logic` convention plugin

A precompiled script plugin `metamorph.kotlin-jvm` in an included build `build-logic` holds the shared setup:
- the Kotlin JVM plugin
- the JDK 25 toolchain
- the `mavenCentral` repository
- JUnit Platform and JUnit Jupiter

The root `settings.gradle.kts` uses `pluginManagement { includeBuild("build-logic") }`. `build-logic` reads the same `gradle/libs.versions.toml`, and the catalog gains a `kotlin-gradle-plugin` library entry, so there is one source of versions.

- *Alternative: `buildSrc`.* It also works, but Gradle now recommends included builds for convention plugins, and with `buildSrc` any change to it invalidates the whole build.
- *Alternative: `subprojects {}` or `allprojects {}` in the root build script.* This couples projects together and conflicts with configuration-cache and isolated-projects best practice.

### D3: Pull-based port

```kotlin
// dev.reichling.core.port
interface EventStreamConsumer : AutoCloseable {
    fun subscribe(streams: Set<String>)
    fun poll(timeout: kotlin.time.Duration): List<StreamRecord>
    fun commit()
    override fun close()
}
```

With a pull API, the caller (the future pipeline runtime) decides:
- how often to poll and how large a batch to process, which gives natural backpressure
- when to commit: after the transform and sink write succeed, which is what at-least-once requires

It matches how Kafka, Pulsar, and Kinesis clients work. It hides no threads, and it is easy to test deterministically.

- *Alternative: push-based (`start(handler)`), where the adapter owns the loop.* Every adapter would re-implement the loop, the threading, and the error policy, and the core would lose control over when to commit.
- *Alternative: a Kotlin `Flow<StreamRecord>`.* Idiomatic, but commits need a side channel, and Kafka's consumer must stay on one thread, which a `Flow` would have to enforce with a single-thread dispatcher. A `Flow` view can be added later in `core` as an extension on top of the pull port, with no changes to adapters.

In hexagonal terms, the core calls this port to obtain data, which makes it a *driven* (secondary) port, even though data flows *into* the system. The *driving* side will be the runtime or CLI in `app`.

No `wakeup()` in the port: `poll` takes a bounded timeout, so the owning thread can check a stop flag between polls, stop the loop, and then call `close()`. A port operation that interrupts a poll from another thread is a Kafka-specific idea, and other platforms do not have one.

### D4: Cumulative commit semantics

`commit()` acknowledges *every record delivered so far*. It does not take individual records or batches. This matches Kafka offsets and Kinesis checkpoints, and it keeps the core loop simple: poll, process the whole batch, write it, commit. A platform with per-message acknowledgement, such as Pulsar, can implement cumulative commit by acknowledging every outstanding message.

The adapter never commits implicitly: not on `close`, not on revoke, and not periodically. Unprocessed records are therefore never acknowledged, and the cost is possible duplicates. Sinks have to be idempotent, or later changes have to deduplicate; the record's origin (stream, partition, offset) gives them a key to deduplicate on.

### D5: Raw bytes in `StreamRecord`; decoding is a separate concern

```kotlin
// dev.reichling.core.model
class StreamRecord(
    val key: ByteArray?, val value: ByteArray?,
    val headers: List<StreamRecord.Header>,  // ordered, duplicates allowed
    val timestamp: Instant,
    val origin: RecordOrigin,                // stream, partition, offset
)
```

- `StreamRecord` is a regular class with `equals` and `hashCode` written out using `contentEquals` and `contentHashCode`. A `data class` would compare `ByteArray` fields by reference. A readable `toString` prints sizes, not the contents.
- `Header` is nested in `StreamRecord` so its name does not clash with Kafka's `Header` and `RecordHeader` in the adapter.
- The adapter uses `ByteArrayDeserializer`, so deserialization never fails inside `poll()`. With typed deserializers, Kafka throws `SerializationException` from `poll()` and stalls the partition (the "poison pill" problem). Decoding becomes a separate core step (a codec port in a later change), where a failure is just a record-level error.

### D6: The adapter wraps the `Consumer` interface, with two ways to construct it

```kotlin
class KafkaConsumerAdapter internal constructor(
    private val consumer: Consumer<ByteArray, ByteArray>,   // adaptee
    private val closeTimeout: Duration = 10.seconds,
) : EventStreamConsumer {
    constructor(settings: KafkaConsumerSettings) : this(KafkaConsumer(settings.toProperties()))
}
```

- Production code constructs the adapter from `KafkaConsumerSettings`.
- Unit tests in the same module inject Kafka's own `MockConsumer` through the `internal` constructor. That is the official test double, and it supports `rebalance()` with listener callbacks and records added programmatically.
- Depending on the `Consumer` *interface* rather than the `KafkaConsumer` class is what makes this possible.

`KafkaConsumerSettings.toProperties()` builds the client configuration:
1. It starts from `additionalProperties`.
2. It adds the typed settings.
3. It sets the adapter-owned properties: `enable.auto.commit=false` and the byte-array deserializers.

If `additionalProperties` contains a key the adapter owns, the settings are rejected when constructed; the value is not silently overwritten. A silently ignored `enable.auto.commit=true` would be a hard-to-find correctness bug.

`autoOffsetReset` defaults to `EARLIEST`. For a data pipeline, missing the existing history on first deployment is worse than processing it.

### D7: Explicit offset tracking, synchronous commit, rebalance listener

- After each `poll`, the adapter records `highestDeliveredOffset[topicPartition]`.
- `commit()` calls `commitSync(Map<TopicPartition, OffsetAndMetadata(offset + 1)>)` and clears the map on success. If the map is empty, it returns without calling the broker.
- `subscribe()` registers a `ConsumerRebalanceListener`. Its `onPartitionsRevoked` and `onPartitionsLost` callbacks remove those partitions from the map, so a later `commit()` cannot commit offsets the consumer no longer owns. The new owner re-reads from the last committed offset.
- Kafka invokes rebalance callbacks only inside `poll()`, on the polling thread, so the map needs no synchronization.
- *Alternative: no-argument `commitSync()`, which commits the current positions.* It gives the same result in the normal flow, but explicit offsets are precise after revocation, they make "nothing to commit" detectable, and they keep commit state in the adapter where tests can observe it.
- *Alternative: `commitAsync`.* Higher throughput, but errors arrive later in callbacks and ordering is harder to reason about. Commits happen once per batch, so `commitSync` is cheap enough. Async commits can be an optimization later behind the same port.
- Both Kafka group protocols, `classic` and the KIP-848 `consumer` protocol, call the rebalance listener. The adapter leaves `group.protocol` at the client default and allows overriding it through `additionalProperties`.

### D8: Exception translation in one place

A private `inline fun <T> translating(block: () -> T): T` wraps every call to the adaptee:

| Kafka exception | Becomes |
|---|---|
| `CommitFailedException`, `RebalanceInProgressException` (during commit) | `CommitRejectedException`: the records will be redelivered |
| any other `KafkaException` (including `InterruptException`, which restores the thread's interrupt flag itself) | `EventStreamException` |
| `IllegalStateException`, `IllegalArgumentException` | passed through unchanged (programming errors) |

Both port exception types live in `dev.reichling.core.port` and extend `RuntimeException`, following Kotlin's unchecked style.

Lifecycle checks (subscribed, closed) happen in the adapter before calling the delegate, so the error messages are port-level and do not depend on Kafka internals.

`close()` calls `consumer.close(CloseOptions.timeout(closeTimeout))`. That is the Kafka 4.x API; `close(Duration)` is deprecated.

### D9: Test strategy

| Level | Location | Runs on | What it covers |
|---|---|---|---|
| Unit | `core/src/test` | `./gradlew test` | `StreamRecord` equality and hash code; the check that no Kafka class is loadable |
| Unit | `adapter-kafka/src/test` | `./gradlew test` | settings validation and effective configuration; record mapping; commit offsets; no-op commit; revoke and lost handling; exception translation; lifecycle. All against `MockConsumer`, in milliseconds, without Docker |
| Architecture | `architecture-test/src/test` | `./gradlew test` | layer dependencies, core purity, adapter isolation, port and adapter conventions, platform-free adapter API, non-empty layers (see D11). Without Docker |
| Integration | `adapter-kafka/src/integrationTest` | `./gradlew integrationTest` (and `check`) | end-to-end mapping; earliest reset; subscription filtering; per-partition ordering; empty poll; commit and restart without redelivery; restart without commit and with redelivery. All against a real broker |

- **Separate test suite.** The `integrationTest` suite uses Gradle's `jvm-test-suite` plugin. `check` depends on it. `test` stays Docker-free, so the edit-and-test loop stays fast.
- **Broker.** `org.testcontainers.kafka.KafkaContainer` from `testcontainers-kafka` 2.0.5. Testcontainers 2.x renamed its modules, and the old `org.testcontainers.containers.KafkaContainer` was removed. The image is `apache/kafka-native:4.3.1`, a GraalVM native broker that starts in about a second. The client is `kafka-clients` 4.4.0; Kafka guarantees compatibility between those versions in both directions.
- **One container per test JVM.** A Kotlin `object` holds a lazily started container. Ryuk is disabled because of Podman (see Risks), so Testcontainers falls back to `JVMHookResourceReaper`: a JVM shutdown hook removes the container when the test JVM exits normally. Tests isolate themselves with a random topic name and `group.id` per test instead of restarting the broker. Topics are created through `Admin` with an explicit partition count, because auto-creation would give a single partition.
- **No JUnit extension.** Managing the container lifecycle by hand avoids `testcontainers-junit-jupiter`, which is built against JUnit 5, while this project uses JUnit 6.
- **Waiting for records.** A small helper polls the adapter until it has N records or a deadline passes. This avoids an Awaitility dependency and keeps every wait bounded.
- **Logging.** `slf4j-simple`, on the test runtime classpath only, at `warn` level, keeps Kafka's logs quiet. The production logging backend is chosen later, by `app`.

### D10: Packages

- `dev.reichling.core.model`: `StreamRecord`, `StreamRecord.Header`, `RecordOrigin`
- `dev.reichling.core.port`: `EventStreamConsumer`, `EventStreamException`, `CommitRejectedException`
- `dev.reichling.adapter.kafka`: `KafkaConsumerAdapter`, `KafkaConsumerSettings`, `AutoOffsetReset`, plus internal mapping and translation helpers
- `dev.reichling.app`: `App` (moved from `dev.reichling`)

This follows the existing `dev.reichling` root package (see Open Questions). Each layer in D11 maps to exactly one of these package roots.

### D11: Architecture tests with Konsist in a test-only module

A module named `architecture-test` contains no production code. Its tests use [Konsist](https://docs.konsist.lemonappdev.com/) 0.18.1 with `Konsist.scopeFromProduction()`, which parses the production Kotlin sources of every module in the repository. That scope excludes test sources, so test doubles are free to implement ports. The module needs no project dependencies, so a new module is covered as soon as its sources exist. The tests run in `./gradlew test`.

The rules, one test class each, are specified in the `hexagonal-architecture` spec:

| Rule | Konsist mechanism |
|---|---|
| Layer dependencies (Model, Port, Adapter, App) | `assertArchitecture { … dependsOnNothing() / dependsOn(…) }` |
| Core purity | imports of files in `dev.reichling.core..` checked against an allowlist: `kotlin.`, `java.`, `dev.reichling.core.` |
| Adapter isolation | for each adapter package, its files' imports must not reference another adapter package |
| Port conventions | declarations in `core.port` are interfaces or `*Exception` classes; port interfaces and their functions have KDoc |
| Adapter conventions | classes with a parent interface from `core.port` reside in `dev.reichling.adapter..` and end with `Adapter` |
| Platform-free adapter API | a registry maps each adapter package to its platform prefixes (`kafka` → `org.apache.kafka`). Public and default-visibility functions, constructors, properties, and supertypes must not reference those prefixes; `internal` is exempt. An adapter package missing from the registry fails the test. |
| Non-vacuous rules | every layer has at least one file |

Why test the architecture when the modules already enforce it? The modules make it impossible to import across them unless a dependency is declared. Several things are outside their reach:
- the direction between packages inside a module (model → port)
- naming conventions and KDoc requirements
- whether a public API leaks platform types
- someone adding `implementation(libs.kafka.clients)` to `core/build.gradle.kts`

The tests catch all of these. The two mechanisms complement each other: the compiler fails fast, and the tests explain *which* rule was broken.

Konsist reads imports rather than bytecode, and that matters for the platform-free API rule. Our `KafkaConsumerAdapter` has an `internal` constructor that takes Kafka's `Consumer`. Konsist sees the `internal` modifier and exempts the constructor. A bytecode tool sees the constructor as public and would need a special exception.

- *Alternative: ArchUnit 1.5.1.* Mature, and it analyses bytecode, so it sees every real dependency, including references written with a fully qualified name. Three problems in this project:
  - Kotlin `internal` declarations are public in bytecode, which breaks the platform-free API rule.
  - Kotlin compiles extra classes of its own (`*Kt` file facades, `$Companion` objects, `$WhenMappings`) that the rules would have to filter out.
  - The test module must declare every module as a dependency, so a new adapter module the author forgets to add goes unchecked without anyone noticing.
- *Alternative: ArchUnit with jMolecules (`@SecondaryPort`, `@SecondaryAdapter`, `ensureHexagonal()`).* The architectural roles become explicit in the code, but `core` would gain an annotation dependency, which contradicts "core depends on nothing", and every type would have to be annotated.
- *Alternative: Taikai.* Preset rules built on ArchUnit, so it has the same problems with Kotlin bytecode.
- *Alternative: Konsist and ArchUnit together.* The most thorough option, but the dependency rules overlap and there are two libraries to keep current. Gradle module boundaries already give the bytecode-level guarantee for the rule that matters most, that `core` does not depend on adapters.

## Risks / Trade-offs

- **[Container runtime]** Testcontainers needs a Docker-compatible API. On this machine, `docker` is Podman emulation. → Document the setup in the README:
  - point `DOCKER_HOST` (or `docker.host` in `~/.testcontainers.properties`) at the Podman socket
  - disable Ryuk (`TESTCONTAINERS_RYUK_DISABLED=true`)

  Keep integration tests out of `test`, so unit tests never need a container.
- **[Leftover containers without Ryuk]** The JVM shutdown hook does not run when the test JVM is killed (for example, a cancelled Gradle daemon or `kill -9`), so a broker container can be left running. → It is harmless: every test uses its own random topic and group. Document cleaning it up with `podman ps --filter label=org.testcontainers` and `podman rm -f`.
- **[Konsist sees source, not bytecode]** A reference written with a fully qualified name and no `import` escapes the import-based checks. → For the rule that matters most (`core` must not depend on adapters), the Gradle module boundaries give a compile-time guarantee. The fully qualified style is rare and shows up in code review.
- **[Konsist is pre-1.0 and tied to the Kotlin compiler]** Konsist 0.18.1 embeds Kotlin compiler 2.4.21. A Kotlin upgrade that adds new syntax may need a Konsist upgrade, and its API may change between 0.x releases. → It is confined to the test-only `architecture-test` module, so production code and the other tests are unaffected. Pin it in the version catalog and upgrade it together with Kotlin.
- **[Rules pass vacuously]** Renaming a package can leave a rule matching nothing. → The non-empty-layer check (D11) fails in that case. Group 7 of the tasks also proves each rule against a deliberate violation.
- **[Duplicates from at-least-once delivery]** Rebalances and crashes before `commit()` redeliver records. → This is accepted by design (D4). Origin metadata gives later sinks a key to deduplicate on.
- **[Large batches before commit]** If processing a batch takes longer than `max.poll.interval.ms`, Kafka removes the consumer from the group, and the commit fails with `CommitRejectedException`. → Expose `maxPollRecords` in the settings; the runtime (a later change) owns tuning and error policy.
- **[Client and broker versions differ]** Client 4.4.0 is tested against a 4.3.1 broker image. → Kafka supports compatibility in both directions; bump the image when a 4.4.0 broker image is published.
- **[Testcontainers with JUnit 6]** Avoided by not using the JUnit extension (D9).
- **[Convention plugin and version catalog]** Precompiled script plugins cannot use the type-safe `libs` accessors directly. → Look up the catalog through `VersionCatalogsExtension` inside the plugin, and keep the plugin minimal.
- **[`build-logic` compile time]** The first build compiles the included build. → This is a one-off cost, and the configuration cache keeps later builds fast.

## Migration Plan

This is greenfield work with nothing to migrate.
- `App.kt` and `AppTest.kt` keep their content but move to the `dev.reichling.app` package, and `application.mainClass` becomes `dev.reichling.app.AppKt`. `app` moves onto the convention plugin but does not depend on the new modules yet.
- Rollback: revert the change's commits.

## Open Questions

- **Root package:** keep `dev.reichling` (chosen, to match the scaffold) or move to `dev.reichling.metamorph`? Renaming is cheap now and gets more expensive later. The architecture tests define the layer packages in one place, so a rename changes one constant there, and the non-empty-layer check catches a rename that was forgotten.
- **Commit metadata:** should commits carry metadata (for example, the Metamorph version or model id in `OffsetAndMetadata`) for lineage? This is left for the pipeline-runtime change.
- **Pattern subscriptions:** should the port support subscribing by pattern (Kafka `Pattern`), or should source definitions always list streams explicitly? Not needed until the declarative model exists.
