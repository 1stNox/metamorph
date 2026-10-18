## 1. Build structure (hexagonal modules)

- [ ] 1.1 Add the new entries to `gradle/libs.versions.toml`:
  - versions: `kafka = "4.4.0"`, `testcontainers = "2.0.5"`, `slf4j = "2.0.20"`, `konsist = "0.18.1"`
  - libraries: `kafka-clients`, `testcontainers-kafka`, `slf4j-simple`, `konsist`, and `kotlin-gradle-plugin` (the latter reusing the Kotlin plugin version)
- [ ] 1.2 Create the `build-logic` included build:
  - its own `settings.gradle.kts`, which imports `../gradle/libs.versions.toml` as `libs`
  - a `build.gradle.kts` with `kotlin-dsl` and the Kotlin Gradle plugin dependency
  - a precompiled plugin `metamorph.kotlin-jvm` that sets up the Kotlin JVM plugin, the JDK 25 toolchain, `mavenCentral`, and JUnit Jupiter (taken from the catalog through `VersionCatalogsExtension`) with `useJUnitPlatform()`
- [ ] 1.3 Update the root `settings.gradle.kts`: add `pluginManagement { includeBuild("build-logic") }` and include `core`, `adapter-kafka`, `app`, and `architecture-test`
- [ ] 1.4 Create `core/build.gradle.kts`, which applies only `metamorph.kotlin-jvm` and has no third-party main dependencies
- [ ] 1.5 Create `adapter-kafka/build.gradle.kts`:
  - apply `metamorph.kotlin-jvm`
  - `implementation(project(":core"))` and `implementation(libs.kafka.clients)`
- [ ] 1.6 Create `architecture-test/build.gradle.kts`: apply `metamorph.kotlin-jvm` and add `testImplementation(libs.konsist)`, with no project dependencies and no main sources
- [ ] 1.7 Move `app/build.gradle.kts` onto `metamorph.kotlin-jvm` together with the `application` plugin, removing the duplicated toolchain, repository, and JUnit setup
- [ ] 1.8 Move `App.kt` and `AppTest.kt` to the package `dev.reichling.app`, under `app/src/{main,test}/kotlin/dev/reichling/app/`, and set `application.mainClass` to `dev.reichling.app.AppKt`
- [ ] 1.9 Run `./gradlew build` (with the configuration cache enabled) and confirm all modules compile, the existing test passes, and `./gradlew :app:run` still prints the greeting

## 2. Core: domain model and consumer port

- [ ] 2.1 Create `dev.reichling.core.model.RecordOrigin(stream: String, partition: Int, offset: Long)` as a data class
- [ ] 2.2 Create `dev.reichling.core.model.StreamRecord` and nested `StreamRecord.Header(key: String, value: ByteArray?)`:
  - write `equals` and `hashCode` by hand, comparing byte arrays by content
  - give `toString` a form that shows sizes instead of payload bytes
- [ ] 2.3 Create `dev.reichling.core.port.EventStreamException` (extends `RuntimeException`) and `CommitRejectedException` (extends `EventStreamException`), each with a message and an optional cause
- [ ] 2.4 Create the `dev.reichling.core.port.EventStreamConsumer` interface with `subscribe(Set<String>)`, `poll(kotlin.time.Duration)`, `commit()`, and `close()`. Give the interface and every function KDoc, as the architecture rules require. The KDoc must state:
  - the at-least-once and cumulative-commit contract
  - that no implicit commits happen
  - the single-thread confinement, including `close()`
  - the exceptions each method can throw
- [ ] 2.5 Add `core` unit tests: content-based equality and hash code of `StreamRecord`, including headers and null key/value; and an assertion that `Class.forName("org.apache.kafka.clients.consumer.Consumer")` throws `ClassNotFoundException`

## 3. Kafka adapter: settings

- [ ] 3.1 Create `AutoOffsetReset { EARLIEST, LATEST }` and `KafkaConsumerSettings`:
  - fields: `bootstrapServers`, `groupId`, `clientId`, `autoOffsetReset = EARLIEST`, `maxPollRecords`, `additionalProperties`
  - in the init block, reject blank required fields, a non-positive `maxPollRecords`, and adapter-owned keys in `additionalProperties`, with an `IllegalArgumentException` that names the key
- [ ] 3.2 Implement `KafkaConsumerSettings.toProperties()`: start from `additionalProperties`, add the typed settings, then force `enable.auto.commit=false` and `ByteArrayDeserializer` for keys and values
- [ ] 3.3 Add settings unit tests:
  - blank `groupId` and blank `bootstrapServers` are rejected
  - `enable.auto.commit` (and each other owned key) in `additionalProperties` is rejected, with the key in the message
  - pass-through of `session.timeout.ms`
  - effective configuration has auto-commit off and the byte-array deserializers
  - the default `auto.offset.reset=earliest`

## 4. Kafka adapter: `KafkaConsumerAdapter`

- [ ] 4.1 Create the `KafkaConsumerAdapter` class:
  - an `internal` primary constructor that takes `Consumer<ByteArray, ByteArray>` and a close timeout
  - a public secondary constructor that takes `KafkaConsumerSettings` and builds a `KafkaConsumer`
  - lifecycle state for subscribed and closed
- [ ] 4.2 Implement the internal mapping `ConsumerRecord<ByteArray, ByteArray>.toStreamRecord()`: topic, partition, and offset become the origin; null key and value stay null; headers keep their order and duplicates; the timestamp becomes an `Instant`
- [ ] 4.3 Implement the private `translating {}` helper:
  - `CommitFailedException` and `RebalanceInProgressException` become `CommitRejectedException`
  - any other `KafkaException` becomes `EventStreamException`, keeping the cause
  - `IllegalStateException` and `IllegalArgumentException` are rethrown unchanged
- [ ] 4.4 Implement `subscribe`: reject an empty set; call `consumer.subscribe(streams, rebalanceListener)`, which replaces any previous subscription. The listener's `onPartitionsRevoked` and `onPartitionsLost` remove those partitions from the pending-offset map
- [ ] 4.5 Implement `poll`: check the lifecycle state (subscribed, not closed); call `consumer.poll(timeout.toJavaDuration())`; update the highest delivered offset per `TopicPartition`; return the mapped records in delivery order
- [ ] 4.6 Implement `commit`: check the lifecycle state; return immediately if nothing is pending; otherwise call `commitSync(pending.mapValues { OffsetAndMetadata(it + 1) })` and clear the pending map on success
- [ ] 4.7 Implement `close`: make it idempotent; call `consumer.close(CloseOptions.timeout(closeTimeout))` without committing; mark the adapter closed so later `subscribe`, `poll`, or `commit` calls throw `IllegalStateException`

## 5. Kafka adapter: unit tests with `MockConsumer`

- [ ] 5.1 Write a test fixture: create a `MockConsumer` (earliest reset), assign partitions through `rebalance()` with beginning offsets, and add records with headers, null keys, and null values
- [ ] 5.2 Mapping tests: tombstone; duplicate headers in order; origin topic, partition, and offset; timestamp
- [ ] 5.3 Commit tests:
  - offsets 0–4 on partition 0 and 0–2 on partition 1 commit `{p0: 5, p1: 3}` (check `MockConsumer.committed()`)
  - a second `commit()` with nothing pending does not call `commitSync`; use a spying subclass or a counter
- [ ] 5.4 Rebalance test: deliver from `orders-0`, `rebalance()` it away during the next `poll`, then `commit()`; no offset is committed for `orders-0`. Cover `onPartitionsLost` the same way
- [ ] 5.5 Translation tests:
  - `setPollException(KafkaException)` makes `poll` throw `EventStreamException` with the Kafka exception as cause
  - a `MockConsumer` subclass whose `commitSync` throws `CommitFailedException` makes `commit` throw `CommitRejectedException`
- [ ] 5.6 Lifecycle tests:
  - `poll` before `subscribe` throws `IllegalStateException`
  - `subscribe(emptySet())` throws `IllegalArgumentException`
  - calling `close()` twice is a no-op
  - `poll` after close throws `IllegalStateException`
  - close without commit leaves no committed offsets and closes the mock

## 6. Kafka adapter: Testcontainers integration tests

- [ ] 6.1 Register an `integrationTest` suite in `adapter-kafka` with the `jvm-test-suite` plugin:
  - dependencies: `project()`, `:core`, `kafka-clients`, `testcontainers-kafka`, and `slf4j-simple` (runtime)
  - make `check` depend on it
  - give the Kotlin compilation access to the main source set, so `internal` types are visible
- [ ] 6.2 Create a `KafkaTestBroker` singleton:
  - a lazily started `org.testcontainers.kafka.KafkaContainer("apache/kafka-native:4.3.1")`, with `bootstrapServers`
  - helpers to create a topic with N partitions through `Admin`
  - a helper to produce byte-array records, with headers and an optional partition, through `KafkaProducer`
- [ ] 6.3 Create a test helper `pollUntil(adapter, expectedCount, deadline)` and settings factories that use a random topic and `groupId` per test; add `simplelogger.properties` at `warn` level
- [ ] 6.4 Integration test: end-to-end mapping of key `k1`, value `v1`, and header `source=test` from topic `orders`
- [ ] 6.5 Integration tests:
  - a new group with the default `EARLIEST` reset receives pre-existing records 0–4
  - subscribing to `orders` only never delivers records from `payments`
- [ ] 6.6 Integration tests:
  - 100 records on one partition arrive in offset order without gaps or duplicates
  - `poll(500.milliseconds)` on an empty topic returns an empty list
- [ ] 6.7 Integration tests for at-least-once:
  - poll 10, commit, close; a new adapter in the same group starts at offset 10
  - poll 10, close without commit; a new adapter in the same group redelivers 0–9
- [ ] 6.8 Run `./gradlew integrationTest` against Podman, with `DOCKER_HOST` pointing at the Podman socket and `TESTCONTAINERS_RYUK_DISABLED=true`. Confirm everything passes and no broker container is left running afterwards (`podman ps`)

## 7. Architecture verification (Konsist)

- [ ] 7.1 In `architecture-test/src/test/kotlin/dev/reichling/architecture/`, create a shared `Architecture` object:
  - the layer package constants: `dev.reichling.core.model..`, `dev.reichling.core.port..`, `dev.reichling.adapter..`, `dev.reichling.app..`
  - the production scope (`Konsist.scopeFromProduction()`, filtered to `dev.reichling..`)
  - the adapter platform registry: `dev.reichling.adapter.kafka` → `org.apache.kafka`
- [ ] 7.2 Write the non-vacuity test: each of the four layers contains at least one production file, and the failure message names the empty layer
- [ ] 7.3 Write the layer dependency test with `assertArchitecture`: Model depends on nothing; Port on Model; Adapter on Port and Model; App on all; nothing on App
- [ ] 7.4 Write the core purity test (imports in `dev.reichling.core..` only from `kotlin.`, `java.`, `dev.reichling.core.`, reporting the file and the import) and the adapter isolation test (no adapter package imports another adapter package)
- [ ] 7.5 Write the port convention tests: declarations in `core.port` are interfaces or classes named `*Exception`; port interfaces and each of their functions have KDoc
- [ ] 7.6 Write the adapter convention test: production classes with a parent interface from `dev.reichling.core.port` reside in `dev.reichling.adapter..` and end with `Adapter`
- [ ] 7.7 Write the platform-free adapter API test:
  - public and default-visibility constructors, functions, properties, and supertypes in each registered adapter package reference none of its platform prefixes; `internal` and `private` are exempt
  - every `dev.reichling.adapter.<name>` package that has sources is in the registry
- [ ] 7.8 Run `./gradlew :architecture-test:test` and confirm it passes against the code from groups 2–4
- [ ] 7.9 Prove that each rule can fail by adding a violation, checking that the matching test fails with a clear message, and reverting it. Use a temporary file for each violation:
  - `core.model` imports `core.port`
  - a core file imports `org.slf4j`
  - a data class in `core.port`
  - `KafkaConsumerAdapter`'s constructor made public
  - an unregistered `dev.reichling.adapter.dummy` package
  - a port implementation named `KafkaSource`

  Afterwards, confirm with `git status` that nothing is left over

## 8. Documentation and final verification

- [ ] 8.1 Add a "Development" section to `README.md` covering:
  - the module layout (`core`, `adapter-kafka`, `app`, `architecture-test`) and the dependency rule
  - what the architecture tests enforce, and that a new adapter must be added to the platform registry
  - `./gradlew test` (unit and architecture) versus `./gradlew integrationTest`
  - the Testcontainers setup for Podman: `DOCKER_HOST` or `~/.testcontainers.properties`, `TESTCONTAINERS_RYUK_DISABLED=true`, and cleaning up leftover containers with `podman ps --filter label=org.testcontainers`
- [ ] 8.2 Run `./gradlew check` from a clean state (`--rerun-tasks`) and confirm it passes with the configuration cache; confirm `core`'s runtime classpath has no `org.apache.kafka` artifacts (`./gradlew :core:dependencies --configuration runtimeClasspath`)
