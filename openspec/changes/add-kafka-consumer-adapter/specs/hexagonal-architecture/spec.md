## ADDED Requirements

### Requirement: Layer dependency direction
Production code SHALL be organised into four layers, identified by package:

| Layer | Package |
|---|---|
| Model | `dev.reichling.core.model..` |
| Port | `dev.reichling.core.port..` |
| Adapter | `dev.reichling.adapter..` |
| App | `dev.reichling.app..` |

The allowed dependencies are:
- Model SHALL NOT depend on any other layer.
- Port SHALL depend on Model only.
- Adapter SHALL depend on Port and Model only.
- App MAY depend on all other layers, because it is the composition root.
- No layer SHALL depend on App.

Automated architecture tests SHALL enforce these rules.

#### Scenario: Current codebase conforms
- **WHEN** the architecture tests run against the production sources of this change
- **THEN** all layer dependency checks pass

#### Scenario: Model depends on a port
- **WHEN** a production file in `dev.reichling.core.model` imports a type from `dev.reichling.core.port`
- **THEN** the architecture tests fail and name the offending file

#### Scenario: Core depends on an adapter
- **WHEN** a production file in `dev.reichling.core` imports a type from `dev.reichling.adapter.kafka`
- **THEN** the architecture tests fail

#### Scenario: Adapter depends on the composition root
- **WHEN** a production file in `dev.reichling.adapter.kafka` imports a type from `dev.reichling.app`
- **THEN** the architecture tests fail

### Requirement: Core purity
Production files in `dev.reichling.core..` SHALL import only from `kotlin..`, `java..`, and `dev.reichling.core..`. This keeps every third-party library, including logging, serialization, and platform clients, out of the core. The rule holds even if a library becomes reachable on `core`'s classpath.

#### Scenario: Third-party import in core
- **WHEN** a production file in `dev.reichling.core` imports `org.slf4j.Logger`
- **THEN** the architecture tests fail and name the forbidden import

### Requirement: Adapter isolation
A production file in one adapter package `dev.reichling.adapter.<a>..` SHALL NOT import from a different adapter package `dev.reichling.adapter.<b>..`. Adapters SHALL interact only through ports.

#### Scenario: One adapter imports another
- **WHEN** a production file in `dev.reichling.adapter.postgres` imports a type from `dev.reichling.adapter.kafka`
- **THEN** the architecture tests fail

### Requirement: Port conventions
Every type declared in `dev.reichling.core.port..` SHALL be an interface (a port) or an exception class whose name ends with `Exception`. Every port interface, and every function it declares, SHALL have KDoc.

#### Scenario: Non-port type in the port package
- **WHEN** a production data class is declared in `dev.reichling.core.port`
- **THEN** the architecture tests fail

#### Scenario: Undocumented port function
- **WHEN** a function of `EventStreamConsumer` has no KDoc
- **THEN** the architecture tests fail and name the function

### Requirement: Adapter conventions
Every production class that implements an interface from `dev.reichling.core.port..` SHALL be declared in `dev.reichling.adapter..`, and its name SHALL end with `Adapter`. This requirement applies to production sources only, so test doubles may implement ports anywhere.

#### Scenario: Kafka adapter conforms
- **WHEN** the architecture tests inspect `KafkaConsumerAdapter`
- **THEN** it passes, because it lives in `dev.reichling.adapter.kafka`, implements `EventStreamConsumer`, and ends with `Adapter`

#### Scenario: Port implementation outside the adapter layer
- **WHEN** a production class in `dev.reichling.core` implements `EventStreamConsumer`
- **THEN** the architecture tests fail

#### Scenario: Port implementation with the wrong name
- **WHEN** a production class named `KafkaSource` in `dev.reichling.adapter.kafka` implements `EventStreamConsumer`
- **THEN** the architecture tests fail

### Requirement: Platform-free adapter public API
Each adapter package SHALL be registered in the architecture tests together with the package prefixes of the platform it adapts. For `dev.reichling.adapter.kafka`, that prefix is `org.apache.kafka`. Public and default-visibility declarations in an adapter package SHALL NOT use the adapter's platform types:
- in parameter types
- in return types
- in property types
- in supertypes

Declarations marked `internal` or `private` are exempt. An adapter package with no registration SHALL fail the architecture tests.

#### Scenario: Public constructor takes a Kafka type
- **WHEN** `KafkaConsumerAdapter` declares a public constructor that takes `Consumer<ByteArray, ByteArray>`
- **THEN** the architecture tests fail

#### Scenario: Internal constructor takes a Kafka type
- **WHEN** that constructor is marked `internal`
- **THEN** the architecture tests pass

#### Scenario: Unregistered adapter
- **WHEN** production sources exist in `dev.reichling.adapter.pulsar`, and that package is not registered with its platform prefixes
- **THEN** the architecture tests fail and name the unregistered adapter package

### Requirement: Architecture rules cannot pass vacuously
The architecture tests SHALL fail if the Model, Port, Adapter, or App layer contains no production source file. This way, renaming or moving packages cannot turn the rules into checks that pass trivially.

#### Scenario: Layer package renamed
- **WHEN** `dev.reichling.core.model` is renamed to `dev.reichling.domain`, and the architecture tests are not updated
- **THEN** the architecture tests fail and report that the Model layer is empty

### Requirement: Architecture tests run with the regular test task
The architecture tests SHALL run as part of `./gradlew test` and SHALL NOT require Docker. They SHALL analyse the production sources of every module in the build, including modules added later, without per-module wiring. Test sources SHALL be excluded from the analysis.

#### Scenario: New module is covered automatically
- **WHEN** a new module with production sources in `dev.reichling.adapter.postgres` is added to the build
- **THEN** the next `./gradlew test` run checks its files, without any change to the architecture test module's build file
